package top.sywyar.pixivdownload.multimodesurvey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

@Configuration
public class MultiModeDecisionSurveyPluginConfiguration {

    @Bean
    public MultiModeDecisionSurveyPlugin multiModeDecisionSurveyPlugin() {
        return new MultiModeDecisionSurveyPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(MultiModeDecisionSurveyPlugin.ID)
    public MultiModeDecisionSurveyIdentityController multiModeDecisionSurveyIdentityController(
            InstallIdentityProvider installIdentityProvider) {
        return new MultiModeDecisionSurveyIdentityController(installIdentityProvider);
    }
}
