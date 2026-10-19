package internal.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Set;
import static internal.skills.Contracts.*;

@Service
@Transactional(readOnly = true)
class SkillService {
    private final SkillRepository repository;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final SkillMd md;
    SkillService(SkillRepository repository, EntityManager em, ObjectMapper mapper, SkillMd md) {
        this.repository = repository; this.em = em; this.mapper = mapper; this.md = md;
    }
    Skill get(long id) { return repository.findById(id).orElseThrow(ApiException::missing); }
    Skill published(String name) { return repository.findByNameAndSnapshotIsNotNull(name).orElseThrow(ApiException::missing); }
    Admin detail(long id) { return Admin.from(get(id)); }

    @Transactional
    Admin create(Create input) {
        SkillMd.validate(input.content());
        Skill s = new Skill(); s.name = input.name(); s.description = input.description(); s.content = input.content();
        s.createdAt = s.updatedAt = now();
        return Admin.from(repository.saveAndFlush(s));
    }
    @Transactional
    Admin save(long id, Update input) {
        SkillMd.validate(input.content());
        Skill s = checked(id, input.lockVersion());
        boolean changed = !s.description.equals(input.description()) || !s.content.equals(input.content());
        mutate(s, input.description(), input.content(), s.snapshot, changed);
        return detail(id);
    }
    @Transactional
    Admin publish(long id, long version) {
        Skill s = checked(id, version); SkillMd.validate(s.content);
        boolean changed = !s.matchesSnapshot();
        Skill.Published snapshot = changed ? new Skill.Published(s.name, s.description, s.content, next(version), now()) : s.snapshot;
        mutate(s, s.description, s.content, snapshot, changed);
        return detail(id);
    }
    @Transactional
    Admin unpublish(long id, long version) {
        Skill s = checked(id, version);
        mutate(s, s.description, s.content, null, s.snapshot != null);
        return detail(id);
    }
    @Transactional
    void delete(long id, long version) {
        Skill s = checked(id, version);
        if (s.snapshot != null) throw new ApiException(409, "PUBLISHED_DELETE", "请先下架再删除");
        int count = em.createNativeQuery("DELETE FROM skill WHERE id=:id AND lock_version=:v AND published_snapshot IS NULL")
                .setParameter("id", id).setParameter("v", version).executeUpdate();
        if (count != 1) throw ApiException.conflict();
        em.clear();
    }
    private Skill checked(long id, long version) {
        Skill s = get(id);
        if (s.lockVersion != version) throw ApiException.conflict();
        return s;
    }
    private long next(long version) {
        if (version == Long.MAX_VALUE) throw new ApiException(409, "VERSION_EXHAUSTED", "版本已达上限");
        return version + 1;
    }
    private Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private void mutate(Skill s, String description, String content, Skill.Published snapshot, boolean changed) {
        // The version predicate is part of the database write, including no-op requests.
        String sql = changed ? "UPDATE skill SET description=:d, content=:c, published_snapshot=:s, published_at=:p, updated_at=:u, lock_version=:n WHERE id=:id AND lock_version=:v"
                : "UPDATE skill SET lock_version=lock_version WHERE id=:id AND lock_version=:v";
        var q = em.createNativeQuery(sql).setParameter("id", s.id).setParameter("v", s.lockVersion);
        if (changed) {
            try {
                q.setParameter("d", description).setParameter("c", content)
                    .setParameter("s", snapshot == null ? null : mapper.writeValueAsString(snapshot))
                    .setParameter("p", snapshot == null ? null : snapshot.publishedAt())
                    .setParameter("u", now()).setParameter("n", next(s.lockVersion));
            } catch (JsonProcessingException e) { throw new IllegalStateException("Snapshot serialization failed", e); }
        }
        if (q.executeUpdate() != 1) throw ApiException.conflict();
        // MySQL REPEATABLE READ would otherwise return the earlier entity from the first-level cache.
        em.clear();
    }
    Page<Admin> adminList(String keyword, String status, int page, int pageSize) {
        var result = repository.findAll(filter(keyword, status, false), paging(page, pageSize));
        return new Page<>(result.map(Admin::from).getContent(), page, pageSize, result.getTotalElements());
    }
    Page<Metadata> clientList(String keyword, int page, int pageSize) {
        var result = repository.findAll(filter(keyword, null, true), paging(page, pageSize));
        return new Page<>(result.map(this::metadata).getContent(), page, pageSize, result.getTotalElements());
    }
    Metadata metadata(Skill s) {
        var p = s.snapshot;
        return new Metadata(s.id.toString(), p.name(), p.description(), p.publishedVersion(), p.publishedAt(), "/api/v1/skills/" + p.name() + "/SKILL.md");
    }
    Detail clientDetail(String name) {
        Skill s = published(name); var m = metadata(s);
        return new Detail(m.id(), m.name(), m.description(), m.publishedVersion(), m.publishedAt(), m.skillMdPath(), md.generate(s.snapshot));
    }
    String raw(String name) { return md.generate(published(name).snapshot); }
    private PageRequest paging(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw ApiException.bad("page 为 1–100000，pageSize 为 1–100");
        return PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
    }
    private Specification<Skill> filter(String keyword, String status, boolean client) {
        if (keyword != null && keyword.length() > 128) throw ApiException.bad("keyword 最多 128 个字符");
        if (status != null && !Set.of("UNPUBLISHED", "PUBLISHED", "MODIFIED").contains(status)) throw ApiException.bad("status 不合法");
        String text = keyword == null ? "" : keyword.strip();
        String like = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return (root, query, cb) -> {
            var conditions = new ArrayList<Predicate>();
            var snapshot = root.get("snapshot");
            Expression<String> description = client ? cb.function("JSON_UNQUOTE", String.class,
                    cb.function("JSON_EXTRACT", String.class, snapshot, cb.literal("$.description"))) : root.get("description");
            if (client) conditions.add(cb.isNotNull(snapshot));
            if (!text.isEmpty()) conditions.add(cb.or(cb.like(root.get("name"), like, '!'), cb.like(description, like, '!')));
            if (status != null) {
                var equal = cb.and(cb.equal(root.get("description"), cb.function("JSON_UNQUOTE", String.class,
                        cb.function("JSON_EXTRACT", String.class, snapshot, cb.literal("$.description")))),
                        cb.equal(root.get("content"), cb.function("JSON_UNQUOTE", String.class,
                        cb.function("JSON_EXTRACT", String.class, snapshot, cb.literal("$.content")))));
                conditions.add(switch (status) {
                    case "UNPUBLISHED" -> cb.isNull(snapshot);
                    case "PUBLISHED" -> cb.and(cb.isNotNull(snapshot), equal);
                    default -> cb.and(cb.isNotNull(snapshot), cb.not(equal));
                });
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }
}
