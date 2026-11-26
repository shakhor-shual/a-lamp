(function (hidden) {
    if (window.location.origin !== "http://localhost:8766") return;

    const key = Symbol.for("ai-lamp.chat-keyboard");
    const active = window[key];
    if (!hidden) {
        if (active) {
            active.restore();
            delete window[key];
        }
        return;
    }
    if (active) return;

    const fields = "input, textarea, [contenteditable]";
    const textTypes = new Set(["text", "search", "url", "tel", "email", "password", "number"]);
    const originalModes = new Map();

    function restoreField(field) {
        const previous = originalModes.get(field);
        if (field.getAttribute("inputmode") === "none") {
            if (previous === null) field.removeAttribute("inputmode");
            else field.setAttribute("inputmode", previous);
        }
        originalModes.delete(field);
    }

    function updateField(field) {
        const contentEditable = field.getAttribute("contenteditable")?.toLowerCase();
        const editable =
            (field.matches("textarea") && !field.disabled && !field.readOnly) ||
            (field.matches("input") && textTypes.has(field.type) && !field.disabled && !field.readOnly) ||
            contentEditable === "" || contentEditable === "true" || contentEditable === "plaintext-only";
        if (!editable) {
            if (originalModes.has(field)) restoreField(field);
            return;
        }
        const current = field.getAttribute("inputmode");
        if (current === "none") return;
        originalModes.set(field, current);
        field.setAttribute("inputmode", "none");
    }

    function updateTree(node) {
        if (!(node instanceof Element)) return;
        if (node.matches(fields)) updateField(node);
        node.querySelectorAll(fields).forEach(updateField);
    }

    function updateTarget(event) {
        if (!(event.target instanceof Element)) return;
        const field = event.target.closest(fields);
        if (field) updateField(field);
    }

    const observer = new MutationObserver(records => {
        let removed = false;
        for (const record of records) {
            if (record.type === "attributes") updateField(record.target);
            else {
                record.addedNodes.forEach(updateTree);
                removed ||= record.removedNodes.length > 0;
            }
        }
        if (removed) {
            for (const field of originalModes.keys()) {
                if (!field.isConnected) restoreField(field);
            }
        }
    });

    updateTree(document.documentElement);
    document.addEventListener("pointerdown", updateTarget, true);
    document.addEventListener("focusin", updateTarget, true);
    observer.observe(document.documentElement, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ["contenteditable", "disabled", "inputmode", "readonly", "type"],
    });
    window[key] = {
        restore() {
            observer.disconnect();
            document.removeEventListener("pointerdown", updateTarget, true);
            document.removeEventListener("focusin", updateTarget, true);
            for (const field of originalModes.keys()) restoreField(field);
        },
    };
})
