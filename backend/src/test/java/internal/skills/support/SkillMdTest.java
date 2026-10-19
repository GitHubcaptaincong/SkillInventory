package internal.skills.support;

import internal.skills.domain.Skill;
import internal.skills.exception.ApiException;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SkillMdTest {
    @Test void yamlRoundTripAndContentPreservation() {
        String description = "中文: \"引号\"\n第二行 # 注释？\n---\n多行";
        String content = "  缩进\r\n\r\n```java\n  int a = 1;  \n```\n";
        SkillMd.validate(content);
        var p = new Skill.Published("java-review", description, content, 3, Instant.EPOCH);
        var md = new SkillMd();
        String output = md.generate(p);
        int end = output.indexOf("\n---\n", 4);
        Map<String, String> parsed = new Yaml().load(output.substring(4, end));
        assertThat(parsed).containsEntry("name", "java-review").containsEntry("description", description);
        assertThat(output.substring(end + 6)).isEqualTo(content);
        assertThat(md.generate(p)).isEqualTo(output);
    }
    @Test void utf8AndFrontmatterValidation() {
        assertThatThrownBy(() -> SkillMd.validate("中".repeat(87382))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SkillMd.validate(" \n\t")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SkillMd.validate("\uFEFF\n---\nname: x\n---\n正文")).isInstanceOf(ApiException.class);
        assertThatCode(() -> SkillMd.validate("a".repeat(262144))).doesNotThrowAnyException();
    }
}
