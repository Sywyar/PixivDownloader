package top.sywyar.pixivdownload.novel.narration;

import java.util.Locale;

public final class UploadedAudioValidator {

    private static final double MAX_SECONDS = 60.0;
    private static final int MIN_SAMPLE_RATE = 8_000;
    private static final int MAX_SAMPLE_RATE = 96_000;
    private static final int MAX_CHANNELS = 2;
    private static final long MAX_DECODED_PCM_BYTES = 32L * 1024L * 1024L;
    private static final String DECLARATION_CONFLICT = "__conflict__";
    private static final int MIN_MP3_FRAMES = 3;
    private static final int MAX_MP3_FRAMES = 6_000;
    private static final int[] MPEG1_LAYER3_BITRATES = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };
    private static final int[] MPEG2_LAYER3_BITRATES = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    };
    private static final int[] MPEG1_SAMPLE_RATES = {44_100, 48_000, 32_000};

    private UploadedAudioValidator() {
    }

    public static Result validate(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        Result wav = validateWav(data);
        if (wav != null) {
            return wav;
        }
        return validateMp3(data);
    }

    private static Result validateWav(byte[] data) {
        if (data.length < 44 || !fourCc(data, 0, "RIFF") || !fourCc(data, 8, "WAVE")) {
            return null;
        }
        long riffSize = readLEUInt(data, 4);
        if (riffSize + 8L != data.length) {
            return null;
        }
        int audioFormat = 0;
        int channels = 0;
        int sampleRate = 0;
        int byteRate = 0;
        int blockAlign = 0;
        int bitsPerSample = 0;
        long dataSize = -1;
        int pos = 12;
        while (pos + 8 <= data.length) {
            long chunkSize = readLEUInt(data, pos + 4);
            long body = pos + 8L;
            long next = body + chunkSize + (chunkSize & 1L);
            if (next > data.length) {
                return null;
            }
            if (fourCc(data, pos, "fmt ") && chunkSize >= 16) {
                audioFormat = readLEUShort(data, (int) body);
                channels = readLEUShort(data, (int) body + 2);
                sampleRate = (int) readLEUInt(data, (int) body + 4);
                byteRate = (int) readLEUInt(data, (int) body + 8);
                blockAlign = readLEUShort(data, (int) body + 12);
                bitsPerSample = readLEUShort(data, (int) body + 14);
            } else if (fourCc(data, pos, "data")) {
                dataSize = chunkSize;
            }
            pos = (int) next;
        }
        if (audioFormat != 1 || dataSize <= 0) {
            return null;
        }
        if (!basicAudioShapeAllowed(sampleRate, channels)) {
            return null;
        }
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
            return null;
        }
        int expectedBlockAlign = channels * bitsPerSample / 8;
        if (blockAlign != expectedBlockAlign || byteRate != sampleRate * expectedBlockAlign) {
            return null;
        }
        double seconds = dataSize / (double) byteRate;
        if (!durationAllowed(seconds) || dataSize > MAX_DECODED_PCM_BYTES) {
            return null;
        }
        return new Result("wav", seconds, sampleRate, channels);
    }

    private static Result validateMp3(byte[] data) {
        int end = trimId3v1End(data);
        int pos = skipId3v2(data, end);
        if (pos < 0 || pos >= end) {
            return null;
        }
        int frames = 0;
        int sampleRate = 0;
        int channels = 0;
        double seconds = 0.0;
        while (pos < end) {
            Frame frame = parseMp3Frame(data, pos, end);
            if (frame == null) {
                return null;
            }
            frames++;
            if (frames > MAX_MP3_FRAMES) {
                return null;
            }
            if (sampleRate == 0) {
                sampleRate = frame.sampleRate();
                channels = frame.channels();
            } else if (sampleRate != frame.sampleRate() || channels != frame.channels()) {
                return null;
            }
            seconds += frame.samplesPerFrame() / (double) frame.sampleRate();
            if (!durationAllowed(seconds)) {
                return null;
            }
            pos += frame.length();
        }
        if (frames < MIN_MP3_FRAMES || !basicAudioShapeAllowed(sampleRate, channels)) {
            return null;
        }
        long decodedBytes = (long) Math.ceil(seconds * sampleRate * channels * 2.0);
        if (decodedBytes > MAX_DECODED_PCM_BYTES) {
            return null;
        }
        return new Result("mp3", seconds, sampleRate, channels);
    }

    public static String declaredExtension(String contentType, String filename) {
        String fromType = extensionFromContentType(contentType);
        String fromName = extensionFromFilename(filename);
        if (fromType != null && fromName != null && !fromType.equals(fromName)) {
            return DECLARATION_CONFLICT;
        }
        return fromType != null ? fromType : fromName;
    }

    private static String extensionFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if ("audio/wav".equals(ct) || "audio/x-wav".equals(ct) || "audio/wave".equals(ct)) {
            return "wav";
        }
        if ("audio/mpeg".equals(ct) || "audio/mp3".equals(ct) || "audio/x-mpeg".equals(ct)) {
            return "mp3";
        }
        return null;
    }

    private static String extensionFromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav")) {
            return "wav";
        }
        if (name.endsWith(".mp3")) {
            return "mp3";
        }
        return null;
    }

    private static boolean basicAudioShapeAllowed(int sampleRate, int channels) {
        return sampleRate >= MIN_SAMPLE_RATE
                && sampleRate <= MAX_SAMPLE_RATE
                && channels >= 1
                && channels <= MAX_CHANNELS;
    }

    private static boolean durationAllowed(double seconds) {
        return seconds > 0.0 && seconds <= MAX_SECONDS;
    }

    private static int trimId3v1End(byte[] data) {
        int end = data.length;
        if (end >= 128 && data[end - 128] == 'T' && data[end - 127] == 'A' && data[end - 126] == 'G') {
            return end - 128;
        }
        return end;
    }

    private static int skipId3v2(byte[] data, int end) {
        if (end < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') {
            return 0;
        }
        if ((data[6] & 0x80) != 0 || (data[7] & 0x80) != 0
                || (data[8] & 0x80) != 0 || (data[9] & 0x80) != 0) {
            return -1;
        }
        int size = ((data[6] & 0x7F) << 21)
                | ((data[7] & 0x7F) << 14)
                | ((data[8] & 0x7F) << 7)
                | (data[9] & 0x7F);
        int footer = (data[5] & 0x10) != 0 ? 10 : 0;
        int start = 10 + size + footer;
        return start <= end ? start : -1;
    }

    private static Frame parseMp3Frame(byte[] data, int offset, int end) {
        if (offset + 4 > end) {
            return null;
        }
        int header = ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
        if ((header & 0xFFE0_0000) != 0xFFE0_0000) {
            return null;
        }
        int versionBits = (header >> 19) & 0x03;
        int layerBits = (header >> 17) & 0x03;
        int bitrateIndex = (header >> 12) & 0x0F;
        int sampleRateIndex = (header >> 10) & 0x03;
        int padding = (header >> 9) & 0x01;
        int channelMode = (header >> 6) & 0x03;
        if (versionBits == 1 || layerBits != 1 || sampleRateIndex == 3) {
            return null;
        }
        int bitrate = bitrate(versionBits, bitrateIndex);
        int sampleRate = sampleRate(versionBits, sampleRateIndex);
        if (bitrate <= 0 || sampleRate <= 0) {
            return null;
        }
        int length = ((versionBits == 3 ? 144 : 72) * bitrate * 1000) / sampleRate + padding;
        if (length <= 4 || offset + length > end) {
            return null;
        }
        int samplesPerFrame = versionBits == 3 ? 1152 : 576;
        int channels = channelMode == 3 ? 1 : 2;
        return new Frame(length, sampleRate, samplesPerFrame, channels);
    }

    private static int bitrate(int versionBits, int index) {
        return versionBits == 3 ? MPEG1_LAYER3_BITRATES[index] : MPEG2_LAYER3_BITRATES[index];
    }

    private static int sampleRate(int versionBits, int index) {
        int base = MPEG1_SAMPLE_RATES[index];
        return switch (versionBits) {
            case 3 -> base;
            case 2 -> base / 2;
            case 0 -> base / 4;
            default -> 0;
        };
    }

    private static boolean fourCc(byte[] data, int offset, String value) {
        return offset + 4 <= data.length
                && data[offset] == value.charAt(0)
                && data[offset + 1] == value.charAt(1)
                && data[offset + 2] == value.charAt(2)
                && data[offset + 3] == value.charAt(3);
    }

    private static long readLEUInt(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static int readLEUShort(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public record Result(String extension, double seconds, int sampleRate, int channels) {
    }

    private record Frame(int length, int sampleRate, int samplesPerFrame, int channels) {
    }
}
