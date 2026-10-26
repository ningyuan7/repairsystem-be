package com.gaofeng.storage;

import com.gaofeng.common.exception.BusinessException;
import com.gaofeng.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

@Component
public class ImageSanitizer {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public record Image(byte[] bytes, String contentType, String extension) {}

    public Image sanitize(byte[] bytes, String contentType) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("图片大小必须在 1 字节到 5MB 之间");
        String format = switch (contentType == null ? "" : contentType) {
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            default -> throw invalid("头像和知识库图片仅支持 JPG、PNG");
        };
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("图片内容无法识别");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().toLowerCase(Locale.ROOT).equals(format))
                    throw invalid("图片内容与声明格式不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 16000000)
                    throw invalid("图片尺寸不能超过 4096×4096 或 1600 万像素");
                var decoded = reader.read(0);
                var output = new ByteArrayOutputStream();
                if (decoded == null || !ImageIO.write(decoded, format, output)) throw invalid("图片解码失败");
                byte[] clean = output.toByteArray();
                if (clean.length > MAX_BYTES) throw invalid("处理后的图片超过 5MB");
                return new Image(clean, contentType, format.equals("jpeg") ? ".jpg" : ".png");
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException ex) { throw invalid("图片文件损坏或格式不正确"); }
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
}
