package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.SelectionMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 桌面文档事件端点索引与不可信事件值校验。
 */
final class DesktopUiEventProtocol {
    private DesktopUiEventProtocol() {
    }

    static Map<String, EventEndpoint> index(DesktopUiDocument document) {
        Map<String, EventEndpoint> endpoints = new LinkedHashMap<>();
        document.pages().forEach(page -> {
            indexNode(page.content(), endpoints);
            page.floatingAction().ifPresent(node -> indexNode(node, endpoints));
        });
        for (DesktopUiDocument.Dialog dialog : document.dialogs()) {
            indexNode(dialog.content(), endpoints);
            if (dialog.dismissible()) {
                putEndpoint(
                        endpoints,
                        dialog.id(),
                        new EventEndpoint(
                                dialog.dismissActionId(),
                                DesktopUiNode.EventType.ACTIVATE,
                                true,
                                null
                        )
                );
            }
        }
        document.shortcuts().forEach(shortcut -> putEndpoint(
                endpoints,
                shortcut.id(),
                new EventEndpoint(
                        shortcut.actionId(),
                        DesktopUiNode.EventType.ACTIVATE,
                        true,
                        null
                )
        ));
        document.tray().ifPresent(tray -> tray.items().stream().filter(item -> item.role() == DesktopUiDocument.TrayItemRole.DISPATCH).forEach(
                item -> putEndpoint(
                        endpoints,
                        item.id(),
                        new EventEndpoint(
                                item.actionId(),
                                DesktopUiNode.EventType.ACTIVATE,
                                true,
                                null
                        )
                )));
        return Map.copyOf(endpoints);
    }

    private static void indexNode(
            DesktopUiNode node,
            Map<String, EventEndpoint> endpoints
    ) {
        EventEndpoint endpoint = null;
        if (node instanceof DesktopUiNode.TextInput value) {
            endpoint = endpoint(
                    value.bindingId(),
                    DesktopUiNode.EventType.CHANGE,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Toggle value) {
            endpoint = endpoint(
                    value.bindingId(),
                    DesktopUiNode.EventType.CHANGE,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Choice value) {
            endpoint = endpoint(
                    value.bindingId(),
                    DesktopUiNode.EventType.SELECTION,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.NumberInput value) {
            endpoint = endpoint(
                    value.bindingId(),
                    DesktopUiNode.EventType.CHANGE,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Table value) {
            endpoint = endpoint(
                    value.bindingId(),
                    DesktopUiNode.EventType.SELECTION,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Tree value) {
            endpoint = endpoint(
                    value.bindingId(),
                    DesktopUiNode.EventType.SELECTION,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Button value) {
            endpoint = endpoint(
                    value.actionId(),
                    DesktopUiNode.EventType.ACTIVATE,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Link value) {
            endpoint = endpoint(
                    value.actionId(),
                    DesktopUiNode.EventType.ACTIVATE,
                    value.enabled(),
                    value
            );
        } else if (node instanceof DesktopUiNode.Surface value && value.actionId() != null) {
            endpoint = endpoint(
                    value.actionId(),
                    DesktopUiNode.EventType.ACTIVATE,
                    true,
                    value
            );
        }
        if (endpoint != null) {
            putEndpoint(endpoints, node.id(), endpoint);
        }
        node.childNodes().forEach(child -> indexNode(child, endpoints));
    }

    private static EventEndpoint endpoint(
            String targetId,
            DesktopUiNode.EventType eventType,
            boolean enabled,
            DesktopUiNode node
    ) {
        return new EventEndpoint(
                targetId,
                eventType,
                enabled,
                node
        );
    }

    private static void putEndpoint(
            Map<String, EventEndpoint> endpoints,
            String id,
            EventEndpoint endpoint
    ) {
        if (endpoints.putIfAbsent(id, endpoint) != null) {
            throw new IllegalArgumentException("duplicate desktop UI event endpoint id: " + id);
        }
    }

    static String validate(EventEndpoint endpoint, DesktopUiNode.Event event) {
        if (endpoint == null) return "unknown node";
        if (!endpoint.enabled()) return "node is disabled";
        if (endpoint.eventType() != event.type()) return "event type does not match node";
        DesktopUiNode node = endpoint.node();
        if (node == null || node instanceof DesktopUiNode.Button || node instanceof DesktopUiNode.Link || node instanceof DesktopUiNode.Surface) {
            return event.value().kind() == DesktopUiNode.ValueKind.NONE ? null : "action carries a value";
        }
        if (node instanceof DesktopUiNode.TextInput) {
            if (event.value().kind() != DesktopUiNode.ValueKind.TEXT || event.value().values().size() != 1) {
                return "text input requires one text value";
            }
            return null;
        }
        if (node instanceof DesktopUiNode.Toggle) {
            return event.value().kind() == DesktopUiNode.ValueKind.BOOLEAN && event.value().values().size() == 1 ? null : "toggle requires one boolean value";
        }
        if (node instanceof DesktopUiNode.NumberInput input) {
            if (event.value().kind() != DesktopUiNode.ValueKind.NUMBER || event.value().values().size() != 1) {
                return "number input requires one numeric value";
            }
            try {
                int value = new BigDecimal(first(event.value().values())).intValueExact();
                if (value < input.minimum() || value > input.maximum())
                    return "number is outside bounds";
                return Math.floorMod(
                        value - input.minimum(),
                        input.step()
                ) == 0 ? null : "number does not align with step";
            } catch (ArithmeticException invalid) {
                return "number must be an integer";
            }
        }
        if (node instanceof DesktopUiNode.Choice choice) {
            String kindError = validateSelectionKind(choice.selectionMode(), event.value());
            if (kindError != null) return kindError;
            Map<String, Boolean> options = choice.options().stream().collect(Collectors.toMap(
                    DesktopUiNode.Option::id,
                    DesktopUiNode.Option::enabled
            ));
            for (String id : event.value().values()) {
                if (!options.containsKey(id)) return "unknown choice option";
                if (!options.get(id)) return "choice option is disabled";
            }
            return null;
        }
        if (node instanceof DesktopUiNode.Table table) {
            String kindError = validateSelectionKind(table.selectionMode(), event.value());
            if (kindError != null) return kindError;
            Set<String> ids = table.rows().stream().map(DesktopUiNode.TableRow::id).collect(
                    Collectors.toSet());
            return ids.containsAll(event.value().values()) ? null : "unknown table row";
        }
        if (node instanceof DesktopUiNode.Tree tree) {
            String kindError = validateSelectionKind(tree.selectionMode(), event.value());
            if (kindError != null) return kindError;
            Set<String> ids = new LinkedHashSet<>();
            collectTreeItemIds(tree.items(), ids);
            return ids.containsAll(event.value().values()) ? null : "unknown tree item";
        }
        return "node does not emit events";
    }

    static Map<String, InteractionSignature> interactionSignatures(
            Map<String, EventEndpoint> endpoints,
            List<DesktopUiPluginSnapshot.Fingerprint> sourceFingerprints
    ) {
        Map<String, InteractionSignature> signatures = new LinkedHashMap<>();
        endpoints.forEach((nodeId, endpoint) -> {
            if (endpoint.eventType() != DesktopUiNode.EventType.ACTIVATE) {
                signatures.put(
                        nodeId,
                        interactionSignature(nodeId, endpoint, sourceFingerprints)
                );
            }
        });
        return Map.copyOf(signatures);
    }

    private static InteractionSignature interactionSignature(
            String nodeId,
            EventEndpoint endpoint,
            List<DesktopUiPluginSnapshot.Fingerprint> sourceFingerprints
    ) {
        DesktopUiNode node = endpoint.node();
        String inputKind = node instanceof DesktopUiNode.TextInput input ? input.inputKind().name() : "";
        Integer minimum = node instanceof DesktopUiNode.NumberInput input ? input.minimum() : null;
        Integer maximum = node instanceof DesktopUiNode.NumberInput input ? input.maximum() : null;
        Integer step = node instanceof DesktopUiNode.NumberInput input ? input.step() : null;
        SelectionMode selectionMode = null;
        List<InteractionOption> options = List.of();
        List<String> selectableIds = List.of();
        if (node instanceof DesktopUiNode.Choice choice) {
            selectionMode = choice.selectionMode();
            options = choice.options().stream().map(option -> new InteractionOption(
                    option.id(),
                    option.enabled()
            )).toList();
        } else if (node instanceof DesktopUiNode.Table table) {
            selectionMode = table.selectionMode();
            selectableIds = table.rows().stream().map(DesktopUiNode.TableRow::id).toList();
        } else if (node instanceof DesktopUiNode.Tree tree) {
            selectionMode = tree.selectionMode();
            List<String> ids = new ArrayList<>();
            collectTreeItemIds(tree.items(), ids);
            selectableIds = List.copyOf(ids);
        }
        return new InteractionSignature(
                node.kind(),
                nodeId,
                endpoint.targetId(),
                endpoint.eventType(),
                endpoint.enabled(),
                inputKind,
                minimum,
                maximum,
                step,
                selectionMode,
                options,
                selectableIds,
                List.copyOf(sourceFingerprints)
        );
    }

    private static String validateSelectionKind(
            SelectionMode mode,
            DesktopUiNode.Value value
    ) {
        DesktopUiNode.ValueKind expected = mode == SelectionMode.SINGLE ? DesktopUiNode.ValueKind.SELECTION : DesktopUiNode.ValueKind.MULTI_SELECTION;
        return value.kind() == expected ? null : "selection kind does not match selection mode";
    }

    static void collectTreeItemIds(
            List<DesktopUiNode.TreeItem> items,
            Collection<String> ids
    ) {
        for (DesktopUiNode.TreeItem item : items) {
            ids.add(item.id());
            collectTreeItemIds(item.children(), ids);
        }
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    record EventEndpoint(
            String targetId,
            DesktopUiNode.EventType eventType,
            boolean enabled,
            DesktopUiNode node
    ) {
    }

    private record InteractionOption(String id, boolean enabled) {
    }

    record InteractionSignature(
            DesktopUiNode.Kind kind,
            String nodeId,
            String targetId,
            DesktopUiNode.EventType eventType,
            boolean enabled,
            String inputKind,
            Integer minimum,
            Integer maximum,
            Integer step,
            SelectionMode selectionMode,
            List<InteractionOption> options,
            List<String> selectableIds,
            List<DesktopUiPluginSnapshot.Fingerprint> sourceFingerprints
    ) {
    }
}
