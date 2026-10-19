package internal.skills;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

@Component
class SkillMd {
    static void validate(String content) {
        if (content == null || content.isBlank()) throw ApiException.bad("content 不能为空白");
        if (content.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            throw ApiException.bad("content 超过 256 KiB UTF-8 字节");
        // A document-level frontmatter opener is forbidden, including BOM and leading blank lines.
        String first = content.replaceFirst("^\\uFEFF", "").stripLeading().split("\\R", 2)[0].stripTrailing();
        if (first.equals("---")) throw ApiException.bad("content 不应包含文档级 YAML frontmatter");
    }

    String generate(Skill.Published p) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        options.setAllowUnicode(true);
        var data = new LinkedHashMap<String, String>();
        data.put("name", p.name());
        data.put("description", p.description());
        return "---\n" + new Yaml(options).dump(data) + "---\n\n" + p.content();
    }
}
