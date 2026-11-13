package aotfixture.plugin;

import org.springframework.stereotype.Component;

@Component
public class NewPluginComponent {
    public String value() { return "new-plugin"; }
}
