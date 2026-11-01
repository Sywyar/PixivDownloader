package top.sywyar.pixivdownload.captcha;

import cloud.tianai.captcha.generator.common.model.dto.CaptchaExchange;
import cloud.tianai.captcha.generator.common.model.dto.CustomData;
import cloud.tianai.captcha.generator.common.model.dto.GenerateParam;
import cloud.tianai.captcha.generator.impl.StandardSliderImageCaptchaGenerator;
import cloud.tianai.captcha.resource.ResourceProviders;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.common.model.dto.ResourceMap;
import cloud.tianai.captcha.resource.impl.DefaultImageCaptchaResourceManager;
import cloud.tianai.captcha.resource.impl.LocalMemoryResourceStore;
import cloud.tianai.captcha.resource.impl.provider.ClassPathResourceProvider;
import cn.hutool.captcha.ShearCaptcha;
import cn.hutool.captcha.generator.CodeGenerator;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.List;
import javax.imageio.ImageIO;

final class CaptchaImages {
    static final int WIDTH = 600;
    static final int HEIGHT = 360;
    static final int TEXT_LENGTH = 5;
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CaptchaImages() { }

    static ImageChallengeStore.Images text() {
        var captcha = new ShearCaptcha(200, 72, TEXT_LENGTH, 4);
        captcha.setGenerator(new CodeGenerator() {
            public String generate() {
                StringBuilder value = new StringBuilder(TEXT_LENGTH);
                for (int i = 0; i < TEXT_LENGTH; i++) value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
                return value.toString();
            }
            public boolean verify(String code, String answer) { return code.equalsIgnoreCase(answer); }
        });
        return new ImageChallengeStore.Images(captcha.getCode(), List.of(captcha.getImageBytes()), 200, 72, 0);
    }

    static ImageChallengeStore.Images slider() {
        var resources = new LocalMemoryResourceStore();
        resources.addResource("SLIDER", new Resource("generated", "background"));
        var template = new ResourceMap("default");
        String path = "META-INF/cut-image/template/slider_" + (RANDOM.nextInt(2) + 1) + "/";
        template.put("active.png", new Resource("classpath", path + "active.png"));
        template.put("fixed.png", new Resource("classpath", path + "fixed.png"));
        resources.addTemplate("SLIDER", template);
        var providers = new ResourceProviders();
        providers.deleteResourceProviderByName("url");
        providers.deleteResourceProviderByName("file");
        providers.registerResourceProvider(new ClassPathResourceProvider() {
            @Override public InputStream doGetResourceInputStream(Resource resource) {
                return CaptchaImages.class.getClassLoader().getResourceAsStream(resource.getData());
            }
        });
        var generator = new StandardSliderImageCaptchaGenerator(new DefaultImageCaptchaResourceManager(resources, providers)) {
            @Override protected BufferedImage getResourceImage(Resource resource) { return background(); }
            @Override protected int randomInt(int origin, int bound) { return origin + RANDOM.nextInt(bound - origin); }
            @Override protected int randomInt(int bound) { return RANDOM.nextInt(bound); }
        };
        // 使用上游同步图像生成接缝，不创建其应用级缓存、清理线程或独立校验端点。
        var exchange = CaptchaExchange.create(new CustomData(), GenerateParam.builder().type("SLIDER").obfuscate(true).build());
        generator.doGenerateCaptchaImage(exchange);
        Point answer = (Point) exchange.getTransferData();
        return new ImageChallengeStore.Images(Integer.toString(answer.x),
                List.of(png(exchange.getBackgroundImage()), png(exchange.getTemplateImage())),
                WIDTH, HEIGHT, exchange.getTemplateImage().getWidth());
    }

    private static BufferedImage background() {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setPaint(new GradientPaint(0, 0, color(), WIDTH, HEIGHT, color()));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 45; i++) {
                graphics.setColor(color());
                int x = RANDOM.nextInt(WIDTH), y = RANDOM.nextInt(HEIGHT), size = 20 + RANDOM.nextInt(100);
                if (i % 2 == 0) graphics.fillOval(x, y, size, size);
                else graphics.fillRoundRect(x, y, size, size / 2, 12, 12);
            }
        } finally { graphics.dispose(); }
        return image;
    }

    private static Color color() { return Color.getHSBColor(RANDOM.nextFloat(), .35f + RANDOM.nextFloat() * .35f, .55f + RANDOM.nextFloat() * .4f); }

    private static byte[] png(BufferedImage image) {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("CAPTCHA_IMAGE_ENCODER_UNAVAILABLE");
            return output.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException("CAPTCHA_IMAGE_UNAVAILABLE"); }
    }
}
