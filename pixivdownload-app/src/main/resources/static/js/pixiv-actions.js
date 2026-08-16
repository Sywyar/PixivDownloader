(function (global) {
    'use strict';

    function splitArguments(source) {
        if (!source.trim()) return [];
        var argumentsList = [];
        var start = 0;
        var quote = '';
        var escaped = false;
        for (var index = 0; index < source.length; index++) {
            var character = source[index];
            if (escaped) {
                escaped = false;
            } else if (character === '\\') {
                escaped = true;
            } else if (quote) {
                if (character === quote) quote = '';
            } else if (character === '"' || character === "'") {
                quote = character;
            } else if (character === ',') {
                argumentsList.push(source.slice(start, index).trim());
                start = index + 1;
            }
        }
        if (quote || escaped) return null;
        argumentsList.push(source.slice(start).trim());
        return argumentsList;
    }

    function parseArgument(source, event, element) {
        if (source === 'this') return element;
        if (source === 'event') return event;
        if (source === 'true') return true;
        if (source === 'false') return false;
        if (source === 'null') return null;
        if (/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(source)) return Number(source);
        if (source.length >= 2 && (source[0] === "'" || source[0] === '"')
                && source[source.length - 1] === source[0]) {
            var quote = source[0];
            var value = '';
            for (var index = 1; index < source.length - 1; index++) {
                var character = source[index];
                if (character !== '\\') {
                    value += character;
                    continue;
                }
                index++;
                if (index >= source.length - 1) return undefined;
                character = source[index];
                if (character !== quote && character !== '\\') return undefined;
                value += character;
            }
            return value;
        }
        return undefined;
    }

    function invoke(event, element, rawAction, actions) {
        var match = /^([A-Za-z_$][\w$]*)\((.*)\)$/.exec(rawAction.trim());
        if (!match || !Object.prototype.hasOwnProperty.call(actions, match[1])) return;
        var argumentSources = splitArguments(match[2]);
        if (!argumentSources) return;
        var args = [];
        for (var index = 0; index < argumentSources.length; index++) {
            var value = parseArgument(argumentSources[index], event, element);
            if (value === undefined) return;
            args.push(value);
        }
        if (element.dataset.pixivPrevent === 'true') event.preventDefault();
        actions[match[1]].apply(element, args);
        if (element.dataset.pixivStop === 'true') {
            if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
            else event.stopPropagation();
        }
    }

    function bind(root, definitions) {
        Object.keys(definitions || {}).forEach(function (eventType) {
            var actions = definitions[eventType];
            var attribute = 'data-pixiv-' + eventType;
            root.addEventListener(eventType, function (event) {
                var target = event.target;
                if (!target || typeof target.closest !== 'function') return;
                var element = target.closest('[' + attribute + ']');
                if (!element || (root !== document && !root.contains(element))) return;
                if (element.dataset.pixivKey && event.key !== element.dataset.pixivKey) return;
                if (element.dataset.pixivSelf === 'true' && event.target !== element) return;
                invoke(event, element, element.getAttribute(attribute) || '', actions);
            }, eventType === 'error' || eventType === 'load');
        });
    }

    global.PixivActions = Object.freeze({ bind: bind });
})(window);
