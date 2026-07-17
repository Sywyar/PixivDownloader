package top.sywyar.pixivdownload.core.db.pathprefix;

/**
 * Encodes stored paths and resolves their persisted representation without exposing the host implementation.
 */
public interface StoredPathCodec {

    String encode(String absolutePath);

    String resolve(String storedValue);
}
