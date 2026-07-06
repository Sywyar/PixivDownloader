package top.sywyar.pixivdownload.douyin.client;

public class DouyinClientException extends Exception {

    private final DouyinClientErrorCode code;

    public DouyinClientException(DouyinClientErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public DouyinClientException(DouyinClientErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DouyinClientErrorCode code() {
        return code;
    }
}
