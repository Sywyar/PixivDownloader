package top.sywyar.pixivdownload.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Paths;

import java.util.*;

/** Discovery hints derived from this document only. Response links describe actual transitions. */
final class RelatedOperations {
    private static final int MAX_RELATED = 3;
    private static final Set<String> GENERIC = Set.of("api", "gui", "tools", "status", "current", "tasks",
            "items", "cancel", "start", "defaults", "preview", "previews", "list", "paged", "history");

    private static final java.util.regex.Pattern ROUTE_IN_TEXT = java.util.regex.Pattern.compile(
            "\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /api/[A-Za-z0-9_{}/-]+");

    private record Entry(String route, String path, Operation operation, Set<String> terms) {}

    static void describe(Paths paths) {
        var byTerm = new HashMap<String, List<Entry>>();
        var entries = new ArrayList<Entry>();
        var routes = new HashMap<String, Operation>();
        var linkTargets = new HashMap<String, String>();
        paths.forEach((path, item) -> item.readOperationsMap().forEach((verb, operation) -> {
            var terms = new HashSet<String>();
            for (String segment : path.substring(1).split("/")) {
                if (segment.isBlank() || segment.startsWith("{")) continue;
                terms.add(segment);
                terms.addAll(List.of(segment.split("-")));
            }
            terms.removeAll(GENERIC);
            String route = verb.name() + " " + path;
            var entry = new Entry(route, path, operation, terms);
            entries.add(entry);
            routes.put(route, operation);
            String pointer = "/paths/" + path.replace("~", "~0").replace("/", "~1") + "/" + verb.name().toLowerCase(Locale.ROOT);
            linkTargets.put("#" + org.springframework.web.util.UriUtils.encodeFragment(pointer, java.nio.charset.StandardCharsets.UTF_8), route);
            if (operation.getOperationId() != null) linkTargets.put(operation.getOperationId(), route);
            for (String term : terms) byTerm.computeIfAbsent(term, ignored -> new ArrayList<>()).add(entry);
        }));
        var alternatives = new HashMap<String, SortedSet<String>>();
        for (var entry : entries) {
            var extensions = entry.operation().getExtensions();
            if (extensions != null && extensions.get("x-program-alternative") instanceof String target && routes.containsKey(target)) {
                alternatives.computeIfAbsent(entry.route(), ignored -> new TreeSet<>()).add(target);
                alternatives.computeIfAbsent(target, ignored -> new TreeSet<>()).add(entry.route());
            }
        }
        for (var source : entries) {
            var scores = new HashMap<String, Integer>();
            for (String term : source.terms()) {
                var matches = byTerm.get(term);
                for (var target : matches) {
                    if (source == target) continue;
                    // Prefer rare resource names and the immediate children of a collection.
                    int weight = 1 + entries.size() / matches.size();
                    if (Objects.equals(source.operation().getTags(), target.operation().getTags())) weight *= 2;
                    if (target.path().startsWith(source.path() + "/") || source.path().startsWith(target.path() + "/")) weight *= 2;
                    scores.merge(target.route(), weight, Integer::sum);
                }
            }
            // Routes such as /api/cancel/{artworkId} may share only their declared API group.
            var groups = source.operation().getTags();
            if (scores.isEmpty() && groups != null && !groups.isEmpty()) {
                var parameters = Arrays.stream(source.path().split("/")).filter(segment -> segment.startsWith("{")).toList();
                for (var target : entries) {
                    var targetGroups = target.operation().getTags();
                    if (source == target || targetGroups == null || Collections.disjoint(groups, targetGroups)) continue;
                    int score = target.route().startsWith("GET ") ? 2 : 1;
                    for (String parameter : parameters) if (target.path().contains(parameter)) score += 2;
                    scores.put(target.route(), score);
                }
            }
            var related = new LinkedHashSet<String>();
            alternatives.getOrDefault(source.route(), Collections.emptySortedSet()).stream().limit(MAX_RELATED).forEachOrdered(related::add);
            var responses = source.operation().getResponses();
            if (responses != null) responses.values().stream()
                    .filter(response -> response.getLinks() != null)
                    .flatMap(response -> response.getLinks().values().stream())
                    .map(link -> linkTargets.get(link.getOperationRef() == null ? link.getOperationId() : link.getOperationRef()))
                    .filter(Objects::nonNull).filter(route -> !route.equals(source.route()) && !related.contains(route))
                    .distinct().limit(MAX_RELATED - related.size()).forEachOrdered(related::add);
            var references = ROUTE_IN_TEXT.matcher(Objects.toString(source.operation().getDescription(), ""));
            while (related.size() < MAX_RELATED && references.find()) {
                String route = references.group();
                if (!route.equals(source.route()) && routes.containsKey(route)) related.add(route);
            }
            scores.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).filter(route -> !related.contains(route))
                    .limit(MAX_RELATED - related.size()).forEachOrdered(related::add);
            if (related.isEmpty()) continue;
            source.operation().addExtension("x-related-operations", List.copyOf(related));
            var guides = related.stream().map(routes::get).map(Operation::getExternalDocs)
                    .filter(Objects::nonNull).map(io.swagger.v3.oas.models.ExternalDocumentation::getUrl)
                    .filter(Objects::nonNull).distinct().toList();
            if (!guides.isEmpty()) source.operation().addExtension("x-related-guides", guides);
        }
    }

    private RelatedOperations() {}
}
