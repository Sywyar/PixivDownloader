package top.sywyar.pixivdownload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PixivDownloadApplication {

    public static void main(String[] args) {
        SpringApplication.run(PixivDownloadApplication.class, args);
    }

}
