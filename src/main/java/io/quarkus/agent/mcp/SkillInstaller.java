package io.quarkus.agent.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

public final class SkillInstaller {

    private static final Logger LOG = Logger.getLogger(SkillInstaller.class);

    static final String DEFAULT_REPO = "quarkusio/skills";
    static final String DEFAULT_BRANCH = "main";

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String SKILLS_PREFIX = "skills/";

    private static final Pattern TREE_PATH_PATTERN = Pattern.compile(
            "\"path\"\\s*:\\s*\"(skills/[^\"]+)\"");
    private static final Pattern TREE_TYPE_PATTERN = Pattern.compile(
            "\"type\"\\s*:\\s*\"(blob|tree)\"");
    private static final Pattern FRONTMATTER_MODE = Pattern.compile(
            "^mode:\\s*\\S+", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_NAME = Pattern.compile(
            "^name:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONTMATTER_DESC = Pattern.compile(
            "^description:\\s*\"?([^\"\\n]+?)\"?\\s*$", Pattern.MULTILINE);

    private SkillInstaller() {
    }

    public record RemoteSkillInfo(String name, String description) {
    }

    public record InstallReport(List<String> installed, List<String> skipped, List<String> failed) {
    }

    public static List<RemoteSkillInfo> listAvailableSkills(String repo, String branch)
            throws IOException, InterruptedException {
        List<String> filePaths = fetchFileTree(repo, branch);
        Map<String, List<String>> skillFiles = groupBySkillName(filePaths);

        List<RemoteSkillInfo> result = new ArrayList<>();
        for (var entry : skillFiles.entrySet()) {
            String skillMdPath = entry.getValue().stream()
                    .filter(p -> p.endsWith("/" + SKILL_FILE_NAME))
                    .findFirst()
                    .orElse(null);
            if (skillMdPath == null) {
                continue;
            }

            String content = fetchFileContent(repo, branch, skillMdPath);
            if (content == null) {
                continue;
            }

            String name = entry.getKey();
            String description = null;
            String frontmatter = extractFrontmatter(content);
            if (frontmatter != null) {
                Matcher nameMatcher = FRONTMATTER_NAME.matcher(frontmatter);
                if (nameMatcher.find()) {
                    name = nameMatcher.group(1).trim();
                }
                Matcher descMatcher = FRONTMATTER_DESC.matcher(frontmatter);
                if (descMatcher.find()) {
                    description = descMatcher.group(1).trim();
                }
            }

            result.add(new RemoteSkillInfo(name, description));
        }

        return result;
    }

    public static InstallReport installSkills(String repo, String branch, String skillName, Path targetDir)
            throws IOException, InterruptedException {
        List<String> filePaths = fetchFileTree(repo, branch);
        Map<String, List<String>> skillFiles = groupBySkillName(filePaths);

        if (skillName != null) {
            List<String> matched = skillFiles.get(skillName);
            if (matched == null) {
                return new InstallReport(List.of(), List.of(), List.of(skillName));
            }
            skillFiles = Map.of(skillName, matched);
        }

        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (var entry : skillFiles.entrySet()) {
            String name = entry.getKey();
            List<String> paths = entry.getValue();

            Path existingSkill = targetDir.resolve(name).resolve(SKILL_FILE_NAME);
            if (isUserCustomized(existingSkill)) {
                skipped.add(name);
                LOG.infof("Skipping '%s' — user-customized skill detected", name);
                continue;
            }

            try {
                String mainContent = null;
                TreeMap<String, String> modules = new TreeMap<>();

                for (String path : paths) {
                    String content = fetchFileContent(repo, branch, path);
                    if (content == null) {
                        continue;
                    }

                    String relativePath = path.substring((SKILLS_PREFIX + name + "/").length());
                    if (relativePath.equals(SKILL_FILE_NAME)) {
                        mainContent = content;
                    } else if (relativePath.startsWith("modules/") || relativePath.startsWith("references/")) {
                        modules.put(relativePath, content);
                    }
                }

                if (mainContent == null) {
                    failed.add(name);
                    continue;
                }

                String inlined = inlineModules(mainContent, modules);

                Path skillDir = targetDir.resolve(name);
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve(SKILL_FILE_NAME), inlined, StandardCharsets.UTF_8);

                installed.add(name);
                LOG.infof("Installed skill '%s' to %s", name, skillDir);
            } catch (IOException e) {
                LOG.warnf("Failed to install skill '%s': %s", name, e.getMessage());
                failed.add(name);
            }
        }

        return new InstallReport(installed, skipped, failed);
    }

    static boolean isUserCustomized(Path skillFile) {
        if (!Files.isRegularFile(skillFile)) {
            return false;
        }
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            String frontmatter = extractFrontmatter(content);
            if (frontmatter == null) {
                return false;
            }
            return FRONTMATTER_MODE.matcher(frontmatter).find();
        } catch (IOException e) {
            return false;
        }
    }

    static String extractFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        return content.substring(3, end);
    }

    static List<String> fetchFileTree(String repo, String branch) throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = HttpClientProvider.getHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.warnf("GitHub tree API returned HTTP %d for %s", response.statusCode(), url);
            return Collections.emptyList();
        }

        return parseTreeResponse(response.body());
    }

    static List<String> parseTreeResponse(String json) {
        List<String> paths = new ArrayList<>();

        int searchFrom = 0;
        while (true) {
            Matcher pathMatcher = TREE_PATH_PATTERN.matcher(json);
            if (!pathMatcher.find(searchFrom)) {
                break;
            }

            String path = pathMatcher.group(1);
            searchFrom = pathMatcher.end();

            int entryStart = json.lastIndexOf('{', pathMatcher.start());
            int entryEnd = json.indexOf('}', pathMatcher.end());
            if (entryStart >= 0 && entryEnd >= 0) {
                String entry = json.substring(entryStart, entryEnd + 1);
                Matcher typeMatcher = TREE_TYPE_PATTERN.matcher(entry);
                if (typeMatcher.find() && "blob".equals(typeMatcher.group(1))) {
                    if (path.endsWith(".md")) {
                        paths.add(path);
                    }
                }
            }
        }

        return paths;
    }

    static String fetchFileContent(String repo, String branch, String path)
            throws IOException, InterruptedException {
        String url = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/" + path;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = HttpClientProvider.getHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        }

        LOG.warnf("Failed to fetch %s: HTTP %d", url, response.statusCode());
        return null;
    }

    static String inlineModules(String mainContent, Map<String, String> modules) {
        if (modules.isEmpty()) {
            return mainContent;
        }

        StringBuilder sb = new StringBuilder(mainContent);
        for (var entry : modules.entrySet()) {
            sb.append("\n\n---\n\n");
            sb.append("<!-- Inlined from ").append(entry.getKey()).append(" -->\n\n");
            sb.append(entry.getValue());
        }

        return sb.toString();
    }

    private static Map<String, List<String>> groupBySkillName(List<String> filePaths) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String path : filePaths) {
            String afterPrefix = path.substring(SKILLS_PREFIX.length());
            int slash = afterPrefix.indexOf('/');
            if (slash > 0) {
                String skillName = afterPrefix.substring(0, slash);
                grouped.computeIfAbsent(skillName, k -> new ArrayList<>()).add(path);
            }
        }
        return grouped;
    }
}
