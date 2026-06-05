package top.sywyar.pixivdownload.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.AiConfig;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.mail.MailConfig;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.notification.NotificationConfig;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.push.PushConfig;
import top.sywyar.pixivdownload.push.channel.bark.BarkConfig;
import top.sywyar.pixivdownload.push.channel.dingtalk.DingTalkConfig;
import top.sywyar.pixivdownload.push.channel.feishu.FeishuConfig;
import top.sywyar.pixivdownload.push.channel.pushplus.PushPlusConfig;
import top.sywyar.pixivdownload.push.channel.serverchan.ServerChanConfig;
import top.sywyar.pixivdownload.push.channel.telegram.TelegramConfig;
import top.sywyar.pixivdownload.push.channel.webhook.WebhookConfig;
import top.sywyar.pixivdownload.push.channel.wecom.WecomConfig;
import top.sywyar.pixivdownload.quota.MultiModeConfig;
import top.sywyar.pixivdownload.setup.SetupProperties;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationTtsConfig;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigReloadService {

    private final DownloadConfig downloadConfig;
    private final MultiModeConfig multiModeConfig;
    private final GuestInviteConfig guestInviteConfig;
    private final SetupProperties setupProperties;
    private final SslConfig sslConfig;
    private final MaintenanceProperties maintenanceProperties;
    private final ProxyConfig proxyConfig;
    private final UpdateConfig updateConfig;
    private final MailConfig mailConfig;
    private final AiConfig aiConfig;
    private final NarrationTtsConfig narrationTtsConfig;
    private final PushConfig pushConfig;
    private final BarkConfig barkConfig;
    private final DingTalkConfig dingTalkConfig;
    private final TelegramConfig telegramConfig;
    private final FeishuConfig feishuConfig;
    private final WecomConfig wecomConfig;
    private final PushPlusConfig pushPlusConfig;
    private final ServerChanConfig serverChanConfig;
    private final WebhookConfig webhookConfig;
    private final NotificationConfig notificationConfig;

    public synchronized ReloadResult reloadHotConfig() throws IOException {
        Binder binder = loadBinder();
        DownloadConfig nextDownload = bind(binder, "download", DownloadConfig::new, DownloadConfig.class);
        MultiModeConfig nextMultiMode = bind(binder, "multi-mode", MultiModeConfig::new, MultiModeConfig.class);
        GuestInviteConfig nextGuestInvite = bind(binder, "guest-invite", GuestInviteConfig::new, GuestInviteConfig.class);
        SetupProperties nextSetup = bind(binder, "setup", SetupProperties::new, SetupProperties.class);
        SslConfig nextSsl = bind(binder, "ssl", SslConfig::new, SslConfig.class);
        MaintenanceProperties nextMaintenance = bind(binder, "maintenance", MaintenanceProperties::new, MaintenanceProperties.class);
        ProxyConfig nextProxy = bind(binder, "proxy", ProxyConfig::new, ProxyConfig.class);
        UpdateConfig nextUpdate = bind(binder, "update", UpdateConfig::new, UpdateConfig.class);
        MailConfig nextMail = bind(binder, "mail", MailConfig::new, MailConfig.class);
        AiConfig nextAi = bind(binder, "ai", AiConfig::new, AiConfig.class);
        NarrationTtsConfig nextNarrationTts = bind(binder, "narration-tts", NarrationTtsConfig::new, NarrationTtsConfig.class);
        PushConfig nextPush = bind(binder, "push", PushConfig::new, PushConfig.class);
        BarkConfig nextBark = bind(binder, "push.bark", BarkConfig::new, BarkConfig.class);
        DingTalkConfig nextDingTalk = bind(binder, "push.dingtalk", DingTalkConfig::new, DingTalkConfig.class);
        TelegramConfig nextTelegram = bind(binder, "push.telegram", TelegramConfig::new, TelegramConfig.class);
        FeishuConfig nextFeishu = bind(binder, "push.feishu", FeishuConfig::new, FeishuConfig.class);
        WecomConfig nextWecom = bind(binder, "push.wecom", WecomConfig::new, WecomConfig.class);
        PushPlusConfig nextPushPlus = bind(binder, "push.pushplus", PushPlusConfig::new, PushPlusConfig.class);
        ServerChanConfig nextServerChan = bind(binder, "push.serverchan", ServerChanConfig::new, ServerChanConfig.class);
        WebhookConfig nextWebhook = bind(binder, "push.webhook", WebhookConfig::new, WebhookConfig.class);
        NotificationConfig nextNotification = bind(binder, "notification", NotificationConfig::new, NotificationConfig.class);

        List<String> applied = new ArrayList<>();
        applyDownloadConfig(nextDownload, applied);
        applyMultiModeConfig(nextMultiMode, applied);
        applyGuestInviteConfig(nextGuestInvite, applied);
        applySetupConfig(nextSetup, applied);
        applySslConfig(nextSsl, applied);
        applyMaintenanceConfig(nextMaintenance, applied);
        applyProxyConfig(nextProxy, applied);
        applyUpdateConfig(nextUpdate, applied);
        applyMailConfig(nextMail, applied);
        applyAiConfig(nextAi, applied);
        applyNarrationTtsConfig(nextNarrationTts, applied);
        applyPushConfig(nextPush, nextBark, nextDingTalk, nextTelegram, applied);
        applyPushChannels(nextFeishu, nextWecom, nextPushPlus, nextServerChan, nextWebhook, applied);
        applyNotificationConfig(nextNotification, applied);

        if (!applied.isEmpty()) {
            log.info("Hot reloaded config keys: {}", applied);
        }
        return new ReloadResult(List.copyOf(applied));
    }

    private Binder loadBinder() throws IOException {
        Path configPath = RuntimeFiles.resolveConfigYamlPath();
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("config.yaml not found: " + configPath.toAbsolutePath());
        }

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = loader.load("runtime-config", new FileSystemResource(configPath));
        for (PropertySource<?> source : loaded) {
            sources.addLast(source);
        }
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    private <T> T bind(Binder binder, String prefix, Supplier<T> fallback, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type)).orElseGet(fallback);
    }

    private void applyDownloadConfig(DownloadConfig next, List<String> applied) {
        applyIfChanged(applied,
                "download.user-flat-folder",
                downloadConfig.isUserFlatFolder(),
                next.isUserFlatFolder(),
                () -> downloadConfig.setUserFlatFolder(next.isUserFlatFolder()));
    }

    private void applyMultiModeConfig(MultiModeConfig next, List<String> applied) {
        MultiModeConfig.Quota currentQuota = ensureQuota(multiModeConfig);
        MultiModeConfig.Quota nextQuota = ensureQuota(next);

        applyIfChanged(applied,
                "multi-mode.quota.enabled",
                currentQuota.isEnabled(),
                nextQuota.isEnabled(),
                () -> currentQuota.setEnabled(nextQuota.isEnabled()));
        applyIfChanged(applied,
                "multi-mode.quota.max-artworks",
                currentQuota.getMaxArtworks(),
                nextQuota.getMaxArtworks(),
                () -> currentQuota.setMaxArtworks(nextQuota.getMaxArtworks()));
        applyIfChanged(applied,
                "multi-mode.quota.reset-period-hours",
                currentQuota.getResetPeriodHours(),
                nextQuota.getResetPeriodHours(),
                () -> currentQuota.setResetPeriodHours(nextQuota.getResetPeriodHours()));
        applyIfChanged(applied,
                "multi-mode.quota.archive-expire-minutes",
                currentQuota.getArchiveExpireMinutes(),
                nextQuota.getArchiveExpireMinutes(),
                () -> currentQuota.setArchiveExpireMinutes(nextQuota.getArchiveExpireMinutes()));
        applyIfChanged(applied,
                "multi-mode.quota.limit-image",
                currentQuota.getLimitImage(),
                nextQuota.getLimitImage(),
                () -> currentQuota.setLimitImage(nextQuota.getLimitImage()));
        applyIfChanged(applied,
                "multi-mode.quota.max-proxy-requests",
                currentQuota.getMaxProxyRequests(),
                nextQuota.getMaxProxyRequests(),
                () -> currentQuota.setMaxProxyRequests(nextQuota.getMaxProxyRequests()));

        applyIfChanged(applied,
                "multi-mode.post-download-mode",
                multiModeConfig.getPostDownloadMode(),
                next.getPostDownloadMode(),
                () -> multiModeConfig.setPostDownloadMode(next.getPostDownloadMode()));
        applyIfChanged(applied,
                "multi-mode.delete-after-hours",
                multiModeConfig.getDeleteAfterHours(),
                next.getDeleteAfterHours(),
                () -> multiModeConfig.setDeleteAfterHours(next.getDeleteAfterHours()));
        applyIfChanged(applied,
                "multi-mode.request-limit-minute",
                multiModeConfig.getRequestLimitMinute(),
                next.getRequestLimitMinute(),
                () -> multiModeConfig.setRequestLimitMinute(next.getRequestLimitMinute()));
        applyIfChanged(applied,
                "multi-mode.static-resource-request-limit-minute",
                multiModeConfig.getStaticResourceRequestLimitMinute(),
                next.getStaticResourceRequestLimitMinute(),
                () -> multiModeConfig.setStaticResourceRequestLimitMinute(next.getStaticResourceRequestLimitMinute()));
        applyIfChanged(applied,
                "multi-mode.limit-page",
                multiModeConfig.getLimitPage(),
                next.getLimitPage(),
                () -> multiModeConfig.setLimitPage(next.getLimitPage()));
    }

    private void applyGuestInviteConfig(GuestInviteConfig next, List<String> applied) {
        applyIfChanged(applied,
                "guest-invite.request-limit-minute",
                guestInviteConfig.getRequestLimitMinute(),
                next.getRequestLimitMinute(),
                () -> guestInviteConfig.setRequestLimitMinute(next.getRequestLimitMinute()));
        applyIfChanged(applied,
                "guest-invite.static-resource-request-limit-minute",
                guestInviteConfig.getStaticResourceRequestLimitMinute(),
                next.getStaticResourceRequestLimitMinute(),
                () -> guestInviteConfig.setStaticResourceRequestLimitMinute(next.getStaticResourceRequestLimitMinute()));
        applyIfChanged(applied,
                "guest-invite.tts-request-limit-minute",
                guestInviteConfig.getTtsRequestLimitMinute(),
                next.getTtsRequestLimitMinute(),
                () -> guestInviteConfig.setTtsRequestLimitMinute(next.getTtsRequestLimitMinute()));
    }

    private void applySetupConfig(SetupProperties next, List<String> applied) {
        applyIfChanged(applied,
                "setup.login-rate-limit-minute",
                setupProperties.getLoginRateLimitMinute(),
                next.getLoginRateLimitMinute(),
                () -> setupProperties.setLoginRateLimitMinute(next.getLoginRateLimitMinute()));
    }

    private void applySslConfig(SslConfig next, List<String> applied) {
        applyIfChanged(applied,
                "ssl.domain",
                sslConfig.getDomain(),
                next.getDomain(),
                () -> sslConfig.setDomain(next.getDomain()));
    }

    private void applyMaintenanceConfig(MaintenanceProperties next, List<String> applied) {
        applyIfChanged(applied,
                "maintenance.enabled",
                maintenanceProperties.isEnabled(),
                next.isEnabled(),
                () -> maintenanceProperties.setEnabled(next.isEnabled()));
        applyMaintenanceSchedule(DayOfWeek.MONDAY, "monday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.TUESDAY, "tuesday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.WEDNESDAY, "wednesday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.THURSDAY, "thursday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.FRIDAY, "friday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.SATURDAY, "saturday", next, applied);
        applyMaintenanceSchedule(DayOfWeek.SUNDAY, "sunday", next, applied);
    }

    private void applyMaintenanceSchedule(DayOfWeek day, String key, MaintenanceProperties next, List<String> applied) {
        MaintenanceProperties.DaySchedule currentSchedule = maintenanceProperties.mutableScheduleFor(day);
        MaintenanceProperties.DaySchedule nextSchedule = next.scheduleFor(day);
        applyIfChanged(applied,
                "maintenance." + key + ".enabled",
                currentSchedule.isEnabled(),
                nextSchedule.isEnabled(),
                () -> currentSchedule.setEnabled(nextSchedule.isEnabled()));
        applyIfChanged(applied,
                "maintenance." + key + ".time",
                currentSchedule.getTime(),
                nextSchedule.getTime(),
                () -> currentSchedule.setTime(nextSchedule.getTime()));
    }

    private void applyProxyConfig(ProxyConfig next, List<String> applied) {
        applyIfChanged(applied,
                "proxy.enabled",
                proxyConfig.isEnabled(),
                next.isEnabled(),
                () -> proxyConfig.setEnabled(next.isEnabled()));
        applyIfChanged(applied,
                "proxy.host",
                proxyConfig.getHost(),
                next.getHost(),
                () -> proxyConfig.setHost(next.getHost()));
        applyIfChanged(applied,
                "proxy.port",
                proxyConfig.getPort(),
                next.getPort(),
                () -> proxyConfig.setPort(next.getPort()));
    }

    private void applyMailConfig(MailConfig next, List<String> applied) {
        applyIfChanged(applied,
                "mail.enabled",
                mailConfig.isEnabled(),
                next.isEnabled(),
                () -> mailConfig.setEnabled(next.isEnabled()));
        applyIfChanged(applied,
                "mail.host",
                mailConfig.getHost(),
                next.getHost(),
                () -> mailConfig.setHost(next.getHost()));
        applyIfChanged(applied,
                "mail.port",
                mailConfig.getPort(),
                next.getPort(),
                () -> mailConfig.setPort(next.getPort()));
        applyIfChanged(applied,
                "mail.security",
                mailConfig.getSecurity(),
                next.getSecurity(),
                () -> mailConfig.setSecurity(next.getSecurity()));
        applyIfChanged(applied,
                "mail.username",
                mailConfig.getUsername(),
                next.getUsername(),
                () -> mailConfig.setUsername(next.getUsername()));
        applyIfChanged(applied,
                "mail.password",
                mailConfig.getPassword(),
                next.getPassword(),
                () -> mailConfig.setPassword(next.getPassword()));
        applyIfChanged(applied,
                "mail.from",
                mailConfig.getFrom(),
                next.getFrom(),
                () -> mailConfig.setFrom(next.getFrom()));
        applyIfChanged(applied,
                "mail.to",
                mailConfig.getTo(),
                next.getTo(),
                () -> mailConfig.setTo(next.getTo()));
        applyIfChanged(applied,
                "mail.socks-proxy",
                mailConfig.getSocksProxy(),
                next.getSocksProxy(),
                () -> mailConfig.setSocksProxy(next.getSocksProxy()));
        applyIfChanged(applied,
                "mail.subject-prefix",
                mailConfig.getSubjectPrefix(),
                next.getSubjectPrefix(),
                () -> mailConfig.setSubjectPrefix(next.getSubjectPrefix()));
    }

    private void applyAiConfig(AiConfig next, List<String> applied) {
        applyIfChanged(applied,
                "ai.enabled",
                aiConfig.isEnabled(),
                next.isEnabled(),
                () -> aiConfig.setEnabled(next.isEnabled()));
        applyIfChanged(applied,
                "ai.base-url",
                aiConfig.getBaseUrl(),
                next.getBaseUrl(),
                () -> aiConfig.setBaseUrl(next.getBaseUrl()));
        applyIfChanged(applied,
                "ai.api-key",
                aiConfig.getApiKey(),
                next.getApiKey(),
                () -> aiConfig.setApiKey(next.getApiKey()));
        applyIfChanged(applied,
                "ai.model",
                aiConfig.getModel(),
                next.getModel(),
                () -> aiConfig.setModel(next.getModel()));
        applyIfChanged(applied,
                "ai.use-proxy",
                aiConfig.isUseProxy(),
                next.isUseProxy(),
                () -> aiConfig.setUseProxy(next.isUseProxy()));
    }

    private void applyNarrationTtsConfig(NarrationTtsConfig next, List<String> applied) {
        applyIfChanged(applied,
                "narration-tts.engine",
                narrationTtsConfig.getEngine(),
                next.getEngine(),
                () -> narrationTtsConfig.setEngine(next.getEngine()));

        NarrationTtsConfig.Voxcpm currentVox = ensureVoxcpm(narrationTtsConfig);
        NarrationTtsConfig.Voxcpm nextVox = ensureVoxcpm(next);
        applyIfChanged(applied,
                "narration-tts.voxcpm.base-url",
                currentVox.getBaseUrl(),
                nextVox.getBaseUrl(),
                () -> currentVox.setBaseUrl(nextVox.getBaseUrl()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.api-key",
                currentVox.getApiKey(),
                nextVox.getApiKey(),
                () -> currentVox.setApiKey(nextVox.getApiKey()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.model",
                currentVox.getModel(),
                nextVox.getModel(),
                () -> currentVox.setModel(nextVox.getModel()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.voice",
                currentVox.getVoice(),
                nextVox.getVoice(),
                () -> currentVox.setVoice(nextVox.getVoice()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.response-format",
                currentVox.getResponseFormat(),
                nextVox.getResponseFormat(),
                () -> currentVox.setResponseFormat(nextVox.getResponseFormat()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.use-proxy",
                currentVox.isUseProxy(),
                nextVox.isUseProxy(),
                () -> currentVox.setUseProxy(nextVox.isUseProxy()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.enable-clone",
                currentVox.isEnableClone(),
                nextVox.isEnableClone(),
                () -> currentVox.setEnableClone(nextVox.isEnableClone()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.clone-mode",
                currentVox.getCloneMode(),
                nextVox.getCloneMode(),
                () -> currentVox.setCloneMode(nextVox.getCloneMode()));
        applyIfChanged(applied,
                "narration-tts.voxcpm.max-new-tokens",
                currentVox.getMaxNewTokens(),
                nextVox.getMaxNewTokens(),
                () -> currentVox.setMaxNewTokens(nextVox.getMaxNewTokens()));

        applyNarrationMimo(next, applied);
        applyNarrationCosyvoice(next, applied);
        applyNarrationFish(next, applied);
        applyNarrationMinimax(next, applied);
        applyNarrationElevenlabs(next, applied);
        applyNarrationQwen(next, applied);
        applyNarrationDoubao(next, applied);
    }

    private void applyNarrationMimo(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Mimo cur = ensureMimo(narrationTtsConfig);
        NarrationTtsConfig.Mimo nxt = ensureMimo(next);
        applyIfChanged(applied, "narration-tts.mimo.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.mimo.api-key", cur.getApiKey(), nxt.getApiKey(),
                () -> cur.setApiKey(nxt.getApiKey()));
        applyIfChanged(applied, "narration-tts.mimo.model", cur.getModel(), nxt.getModel(),
                () -> cur.setModel(nxt.getModel()));
        applyIfChanged(applied, "narration-tts.mimo.voice-design-model", cur.getVoiceDesignModel(), nxt.getVoiceDesignModel(),
                () -> cur.setVoiceDesignModel(nxt.getVoiceDesignModel()));
        applyIfChanged(applied, "narration-tts.mimo.voice-clone-model", cur.getVoiceCloneModel(), nxt.getVoiceCloneModel(),
                () -> cur.setVoiceCloneModel(nxt.getVoiceCloneModel()));
        applyIfChanged(applied, "narration-tts.mimo.voice", cur.getVoice(), nxt.getVoice(),
                () -> cur.setVoice(nxt.getVoice()));
        applyIfChanged(applied, "narration-tts.mimo.response-format", cur.getResponseFormat(), nxt.getResponseFormat(),
                () -> cur.setResponseFormat(nxt.getResponseFormat()));
        applyIfChanged(applied, "narration-tts.mimo.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
        applyIfChanged(applied, "narration-tts.mimo.enable-clone", cur.isEnableClone(), nxt.isEnableClone(),
                () -> cur.setEnableClone(nxt.isEnableClone()));
    }

    private void applyNarrationCosyvoice(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Cosyvoice cur = ensureCosyvoice(narrationTtsConfig);
        NarrationTtsConfig.Cosyvoice nxt = ensureCosyvoice(next);
        applyIfChanged(applied, "narration-tts.cosyvoice.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.cosyvoice.api-key", cur.getApiKey(), nxt.getApiKey(),
                () -> cur.setApiKey(nxt.getApiKey()));
        applyIfChanged(applied, "narration-tts.cosyvoice.model", cur.getModel(), nxt.getModel(),
                () -> cur.setModel(nxt.getModel()));
        applyIfChanged(applied, "narration-tts.cosyvoice.voice", cur.getVoice(), nxt.getVoice(),
                () -> cur.setVoice(nxt.getVoice()));
        applyIfChanged(applied, "narration-tts.cosyvoice.response-format", cur.getResponseFormat(), nxt.getResponseFormat(),
                () -> cur.setResponseFormat(nxt.getResponseFormat()));
        applyIfChanged(applied, "narration-tts.cosyvoice.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
        applyIfChanged(applied, "narration-tts.cosyvoice.enable-clone", cur.isEnableClone(), nxt.isEnableClone(),
                () -> cur.setEnableClone(nxt.isEnableClone()));
    }

    private void applyNarrationFish(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Fish cur = ensureFish(narrationTtsConfig);
        NarrationTtsConfig.Fish nxt = ensureFish(next);
        applyIfChanged(applied, "narration-tts.fish.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.fish.api-key", cur.getApiKey(), nxt.getApiKey(),
                () -> cur.setApiKey(nxt.getApiKey()));
        applyIfChanged(applied, "narration-tts.fish.model", cur.getModel(), nxt.getModel(),
                () -> cur.setModel(nxt.getModel()));
        applyIfChanged(applied, "narration-tts.fish.reference-id", cur.getReferenceId(), nxt.getReferenceId(),
                () -> cur.setReferenceId(nxt.getReferenceId()));
        applyIfChanged(applied, "narration-tts.fish.format", cur.getFormat(), nxt.getFormat(),
                () -> cur.setFormat(nxt.getFormat()));
        applyIfChanged(applied, "narration-tts.fish.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
    }

    private void applyNarrationMinimax(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Minimax cur = ensureMinimax(narrationTtsConfig);
        NarrationTtsConfig.Minimax nxt = ensureMinimax(next);
        applyIfChanged(applied, "narration-tts.minimax.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.minimax.api-key", cur.getApiKey(), nxt.getApiKey(),
                () -> cur.setApiKey(nxt.getApiKey()));
        applyIfChanged(applied, "narration-tts.minimax.group-id", cur.getGroupId(), nxt.getGroupId(),
                () -> cur.setGroupId(nxt.getGroupId()));
        applyIfChanged(applied, "narration-tts.minimax.model", cur.getModel(), nxt.getModel(),
                () -> cur.setModel(nxt.getModel()));
        applyIfChanged(applied, "narration-tts.minimax.voice-id", cur.getVoiceId(), nxt.getVoiceId(),
                () -> cur.setVoiceId(nxt.getVoiceId()));
        applyIfChanged(applied, "narration-tts.minimax.emotion", cur.getEmotion(), nxt.getEmotion(),
                () -> cur.setEmotion(nxt.getEmotion()));
        applyIfChanged(applied, "narration-tts.minimax.format", cur.getFormat(), nxt.getFormat(),
                () -> cur.setFormat(nxt.getFormat()));
        applyIfChanged(applied, "narration-tts.minimax.sample-rate", cur.getSampleRate(), nxt.getSampleRate(),
                () -> cur.setSampleRate(nxt.getSampleRate()));
        applyIfChanged(applied, "narration-tts.minimax.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
    }

    private void applyNarrationElevenlabs(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Elevenlabs cur = ensureElevenlabs(narrationTtsConfig);
        NarrationTtsConfig.Elevenlabs nxt = ensureElevenlabs(next);
        applyIfChanged(applied, "narration-tts.elevenlabs.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.elevenlabs.api-key", cur.getApiKey(), nxt.getApiKey(),
                () -> cur.setApiKey(nxt.getApiKey()));
        applyIfChanged(applied, "narration-tts.elevenlabs.model", cur.getModel(), nxt.getModel(),
                () -> cur.setModel(nxt.getModel()));
        applyIfChanged(applied, "narration-tts.elevenlabs.voice-id", cur.getVoiceId(), nxt.getVoiceId(),
                () -> cur.setVoiceId(nxt.getVoiceId()));
        applyIfChanged(applied, "narration-tts.elevenlabs.output-format", cur.getOutputFormat(), nxt.getOutputFormat(),
                () -> cur.setOutputFormat(nxt.getOutputFormat()));
        applyIfChanged(applied, "narration-tts.elevenlabs.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
    }

    private void applyNarrationQwen(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Qwen cur = ensureQwen(narrationTtsConfig);
        NarrationTtsConfig.Qwen nxt = ensureQwen(next);
        applyIfChanged(applied, "narration-tts.qwen.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.qwen.api-key", cur.getApiKey(), nxt.getApiKey(),
                () -> cur.setApiKey(nxt.getApiKey()));
        applyIfChanged(applied, "narration-tts.qwen.model", cur.getModel(), nxt.getModel(),
                () -> cur.setModel(nxt.getModel()));
        applyIfChanged(applied, "narration-tts.qwen.voice", cur.getVoice(), nxt.getVoice(),
                () -> cur.setVoice(nxt.getVoice()));
        applyIfChanged(applied, "narration-tts.qwen.language-type", cur.getLanguageType(), nxt.getLanguageType(),
                () -> cur.setLanguageType(nxt.getLanguageType()));
        applyIfChanged(applied, "narration-tts.qwen.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
    }

    private void applyNarrationDoubao(NarrationTtsConfig next, List<String> applied) {
        NarrationTtsConfig.Doubao cur = ensureDoubao(narrationTtsConfig);
        NarrationTtsConfig.Doubao nxt = ensureDoubao(next);
        applyIfChanged(applied, "narration-tts.doubao.base-url", cur.getBaseUrl(), nxt.getBaseUrl(),
                () -> cur.setBaseUrl(nxt.getBaseUrl()));
        applyIfChanged(applied, "narration-tts.doubao.app-id", cur.getAppId(), nxt.getAppId(),
                () -> cur.setAppId(nxt.getAppId()));
        applyIfChanged(applied, "narration-tts.doubao.access-token", cur.getAccessToken(), nxt.getAccessToken(),
                () -> cur.setAccessToken(nxt.getAccessToken()));
        applyIfChanged(applied, "narration-tts.doubao.cluster", cur.getCluster(), nxt.getCluster(),
                () -> cur.setCluster(nxt.getCluster()));
        applyIfChanged(applied, "narration-tts.doubao.voice-type", cur.getVoiceType(), nxt.getVoiceType(),
                () -> cur.setVoiceType(nxt.getVoiceType()));
        applyIfChanged(applied, "narration-tts.doubao.encoding", cur.getEncoding(), nxt.getEncoding(),
                () -> cur.setEncoding(nxt.getEncoding()));
        applyIfChanged(applied, "narration-tts.doubao.emotion", cur.getEmotion(), nxt.getEmotion(),
                () -> cur.setEmotion(nxt.getEmotion()));
        applyIfChanged(applied, "narration-tts.doubao.use-proxy", cur.isUseProxy(), nxt.isUseProxy(),
                () -> cur.setUseProxy(nxt.isUseProxy()));
    }

    private static NarrationTtsConfig.Voxcpm ensureVoxcpm(NarrationTtsConfig config) {
        NarrationTtsConfig.Voxcpm voxcpm = config.getVoxcpm();
        if (voxcpm == null) {
            voxcpm = new NarrationTtsConfig.Voxcpm();
            config.setVoxcpm(voxcpm);
        }
        return voxcpm;
    }

    private static NarrationTtsConfig.Mimo ensureMimo(NarrationTtsConfig config) {
        NarrationTtsConfig.Mimo mimo = config.getMimo();
        if (mimo == null) {
            mimo = new NarrationTtsConfig.Mimo();
            config.setMimo(mimo);
        }
        return mimo;
    }

    private static NarrationTtsConfig.Cosyvoice ensureCosyvoice(NarrationTtsConfig config) {
        NarrationTtsConfig.Cosyvoice cosyvoice = config.getCosyvoice();
        if (cosyvoice == null) {
            cosyvoice = new NarrationTtsConfig.Cosyvoice();
            config.setCosyvoice(cosyvoice);
        }
        return cosyvoice;
    }

    private static NarrationTtsConfig.Fish ensureFish(NarrationTtsConfig config) {
        NarrationTtsConfig.Fish fish = config.getFish();
        if (fish == null) {
            fish = new NarrationTtsConfig.Fish();
            config.setFish(fish);
        }
        return fish;
    }

    private static NarrationTtsConfig.Minimax ensureMinimax(NarrationTtsConfig config) {
        NarrationTtsConfig.Minimax minimax = config.getMinimax();
        if (minimax == null) {
            minimax = new NarrationTtsConfig.Minimax();
            config.setMinimax(minimax);
        }
        return minimax;
    }

    private static NarrationTtsConfig.Elevenlabs ensureElevenlabs(NarrationTtsConfig config) {
        NarrationTtsConfig.Elevenlabs elevenlabs = config.getElevenlabs();
        if (elevenlabs == null) {
            elevenlabs = new NarrationTtsConfig.Elevenlabs();
            config.setElevenlabs(elevenlabs);
        }
        return elevenlabs;
    }

    private static NarrationTtsConfig.Qwen ensureQwen(NarrationTtsConfig config) {
        NarrationTtsConfig.Qwen qwen = config.getQwen();
        if (qwen == null) {
            qwen = new NarrationTtsConfig.Qwen();
            config.setQwen(qwen);
        }
        return qwen;
    }

    private static NarrationTtsConfig.Doubao ensureDoubao(NarrationTtsConfig config) {
        NarrationTtsConfig.Doubao doubao = config.getDoubao();
        if (doubao == null) {
            doubao = new NarrationTtsConfig.Doubao();
            config.setDoubao(doubao);
        }
        return doubao;
    }

    private void applyPushConfig(PushConfig nextPush, BarkConfig nextBark, DingTalkConfig nextDingTalk,
                                 TelegramConfig nextTelegram, List<String> applied) {
        applyIfChanged(applied,
                "push.enabled",
                pushConfig.isEnabled(),
                nextPush.isEnabled(),
                () -> pushConfig.setEnabled(nextPush.isEnabled()));

        applyIfChanged(applied,
                "push.bark.enabled",
                barkConfig.isEnabled(),
                nextBark.isEnabled(),
                () -> barkConfig.setEnabled(nextBark.isEnabled()));
        applyIfChanged(applied,
                "push.bark.server",
                barkConfig.getServer(),
                nextBark.getServer(),
                () -> barkConfig.setServer(nextBark.getServer()));
        applyIfChanged(applied,
                "push.bark.device-key",
                barkConfig.getDeviceKey(),
                nextBark.getDeviceKey(),
                () -> barkConfig.setDeviceKey(nextBark.getDeviceKey()));
        applyIfChanged(applied,
                "push.bark.sound",
                barkConfig.getSound(),
                nextBark.getSound(),
                () -> barkConfig.setSound(nextBark.getSound()));
        applyIfChanged(applied,
                "push.bark.use-proxy",
                barkConfig.isUseProxy(),
                nextBark.isUseProxy(),
                () -> barkConfig.setUseProxy(nextBark.isUseProxy()));

        applyIfChanged(applied,
                "push.dingtalk.enabled",
                dingTalkConfig.isEnabled(),
                nextDingTalk.isEnabled(),
                () -> dingTalkConfig.setEnabled(nextDingTalk.isEnabled()));
        applyIfChanged(applied,
                "push.dingtalk.access-token",
                dingTalkConfig.getAccessToken(),
                nextDingTalk.getAccessToken(),
                () -> dingTalkConfig.setAccessToken(nextDingTalk.getAccessToken()));
        applyIfChanged(applied,
                "push.dingtalk.secret",
                dingTalkConfig.getSecret(),
                nextDingTalk.getSecret(),
                () -> dingTalkConfig.setSecret(nextDingTalk.getSecret()));
        applyIfChanged(applied,
                "push.dingtalk.use-proxy",
                dingTalkConfig.isUseProxy(),
                nextDingTalk.isUseProxy(),
                () -> dingTalkConfig.setUseProxy(nextDingTalk.isUseProxy()));

        applyIfChanged(applied,
                "push.telegram.enabled",
                telegramConfig.isEnabled(),
                nextTelegram.isEnabled(),
                () -> telegramConfig.setEnabled(nextTelegram.isEnabled()));
        applyIfChanged(applied,
                "push.telegram.bot-token",
                telegramConfig.getBotToken(),
                nextTelegram.getBotToken(),
                () -> telegramConfig.setBotToken(nextTelegram.getBotToken()));
        applyIfChanged(applied,
                "push.telegram.chat-id",
                telegramConfig.getChatId(),
                nextTelegram.getChatId(),
                () -> telegramConfig.setChatId(nextTelegram.getChatId()));
        applyIfChanged(applied,
                "push.telegram.use-proxy",
                telegramConfig.isUseProxy(),
                nextTelegram.isUseProxy(),
                () -> telegramConfig.setUseProxy(nextTelegram.isUseProxy()));
    }

    private void applyPushChannels(FeishuConfig nextFeishu, WecomConfig nextWecom, PushPlusConfig nextPushPlus,
                                   ServerChanConfig nextServerChan, WebhookConfig nextWebhook, List<String> applied) {
        applyIfChanged(applied, "push.feishu.enabled",
                feishuConfig.isEnabled(), nextFeishu.isEnabled(),
                () -> feishuConfig.setEnabled(nextFeishu.isEnabled()));
        applyIfChanged(applied, "push.feishu.webhook-key",
                feishuConfig.getWebhookKey(), nextFeishu.getWebhookKey(),
                () -> feishuConfig.setWebhookKey(nextFeishu.getWebhookKey()));
        applyIfChanged(applied, "push.feishu.secret",
                feishuConfig.getSecret(), nextFeishu.getSecret(),
                () -> feishuConfig.setSecret(nextFeishu.getSecret()));
        applyIfChanged(applied, "push.feishu.use-proxy",
                feishuConfig.isUseProxy(), nextFeishu.isUseProxy(),
                () -> feishuConfig.setUseProxy(nextFeishu.isUseProxy()));

        applyIfChanged(applied, "push.wecom.enabled",
                wecomConfig.isEnabled(), nextWecom.isEnabled(),
                () -> wecomConfig.setEnabled(nextWecom.isEnabled()));
        applyIfChanged(applied, "push.wecom.key",
                wecomConfig.getKey(), nextWecom.getKey(),
                () -> wecomConfig.setKey(nextWecom.getKey()));
        applyIfChanged(applied, "push.wecom.use-proxy",
                wecomConfig.isUseProxy(), nextWecom.isUseProxy(),
                () -> wecomConfig.setUseProxy(nextWecom.isUseProxy()));

        applyIfChanged(applied, "push.pushplus.enabled",
                pushPlusConfig.isEnabled(), nextPushPlus.isEnabled(),
                () -> pushPlusConfig.setEnabled(nextPushPlus.isEnabled()));
        applyIfChanged(applied, "push.pushplus.token",
                pushPlusConfig.getToken(), nextPushPlus.getToken(),
                () -> pushPlusConfig.setToken(nextPushPlus.getToken()));
        applyIfChanged(applied, "push.pushplus.use-proxy",
                pushPlusConfig.isUseProxy(), nextPushPlus.isUseProxy(),
                () -> pushPlusConfig.setUseProxy(nextPushPlus.isUseProxy()));

        applyIfChanged(applied, "push.serverchan.enabled",
                serverChanConfig.isEnabled(), nextServerChan.isEnabled(),
                () -> serverChanConfig.setEnabled(nextServerChan.isEnabled()));
        applyIfChanged(applied, "push.serverchan.send-key",
                serverChanConfig.getSendKey(), nextServerChan.getSendKey(),
                () -> serverChanConfig.setSendKey(nextServerChan.getSendKey()));
        applyIfChanged(applied, "push.serverchan.use-proxy",
                serverChanConfig.isUseProxy(), nextServerChan.isUseProxy(),
                () -> serverChanConfig.setUseProxy(nextServerChan.isUseProxy()));

        applyIfChanged(applied, "push.webhook.enabled",
                webhookConfig.isEnabled(), nextWebhook.isEnabled(),
                () -> webhookConfig.setEnabled(nextWebhook.isEnabled()));
        applyIfChanged(applied, "push.webhook.url",
                webhookConfig.getUrl(), nextWebhook.getUrl(),
                () -> webhookConfig.setUrl(nextWebhook.getUrl()));
        applyIfChanged(applied, "push.webhook.content-type",
                webhookConfig.getContentType(), nextWebhook.getContentType(),
                () -> webhookConfig.setContentType(nextWebhook.getContentType()));
        applyIfChanged(applied, "push.webhook.body-template",
                webhookConfig.getBodyTemplate(), nextWebhook.getBodyTemplate(),
                () -> webhookConfig.setBodyTemplate(nextWebhook.getBodyTemplate()));
        applyIfChanged(applied, "push.webhook.use-proxy",
                webhookConfig.isUseProxy(), nextWebhook.isUseProxy(),
                () -> webhookConfig.setUseProxy(nextWebhook.isUseProxy()));
    }

    private void applyUpdateConfig(UpdateConfig next, List<String> applied) {
        applyIfChanged(applied,
                "update.enabled",
                updateConfig.isEnabled(),
                next.isEnabled(),
                () -> updateConfig.setEnabled(next.isEnabled()));
        applyIfChanged(applied,
                "update.manifest-url",
                updateConfig.getManifestUrl(),
                next.getManifestUrl(),
                () -> updateConfig.setManifestUrl(next.getManifestUrl()));
        applyIfChanged(applied,
                "update.nightly-manifest-url",
                updateConfig.getNightlyManifestUrl(),
                next.getNightlyManifestUrl(),
                () -> updateConfig.setNightlyManifestUrl(next.getNightlyManifestUrl()));
        applyIfChanged(applied,
                "update.auto-check",
                updateConfig.isAutoCheck(),
                next.isAutoCheck(),
                () -> updateConfig.setAutoCheck(next.isAutoCheck()));
        applyIfChanged(applied,
                "update.check-nightly",
                updateConfig.getCheckNightly(),
                next.getCheckNightly(),
                () -> updateConfig.setCheckNightly(next.getCheckNightly()));
    }

    private static MultiModeConfig.Quota ensureQuota(MultiModeConfig config) {
        MultiModeConfig.Quota quota = config.getQuota();
        if (quota == null) {
            quota = new MultiModeConfig.Quota();
            config.setQuota(quota);
        }
        return quota;
    }

    private void applyNotificationConfig(NotificationConfig next, List<String> applied) {
        for (NotificationScenario scenario : NotificationScenario.values()) {
            String id = scenario.id();
            boolean current = notificationConfig.isScenarioEnabled(id);
            boolean nextEnabled = next.isScenarioEnabled(id);
            applyIfChanged(applied,
                    NotificationConfig.scenarioEnabledKey(id),
                    current,
                    nextEnabled,
                    () -> notificationConfig.setScenarioEnabled(id, nextEnabled));
        }
    }

    private static void applyIfChanged(List<String> applied,
                                       String key,
                                       Object current,
                                       Object next,
                                       Runnable apply) {
        if (Objects.equals(current, next)) {
            return;
        }
        apply.run();
        applied.add(key);
    }

    public record ReloadResult(List<String> appliedKeys) {}
}
