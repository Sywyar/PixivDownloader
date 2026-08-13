package top.sywyar.pixivdownload.multimodesurvey;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

public class MultiModeDecisionSurveyPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new MultiModeDecisionSurveyPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(MultiModeDecisionSurveyPluginConfiguration.class);
    }
}
