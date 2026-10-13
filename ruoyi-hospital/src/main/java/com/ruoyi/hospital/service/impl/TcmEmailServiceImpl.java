package com.ruoyi.hospital.service.impl;

import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.hospital.domain.TcmClinicSetting;
import com.ruoyi.hospital.domain.TcmEmailLog;
import com.ruoyi.hospital.mapper.TcmClinicSettingMapper;
import com.ruoyi.hospital.mapper.TcmEmailLogMapper;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.util.EmailTemplateRegistry;
import com.ruoyi.hospital.util.HospitalFileStorage;

/**
 * 邮件发送 Service实现
 *
 * @author ruoyi
 */
@Service
public class TcmEmailServiceImpl implements ITcmEmailService
{
    private static final Logger log = LoggerFactory.getLogger(TcmEmailServiceImpl.class);
    private static final Pattern WEB_URL_PATTERN = Pattern.compile("(https?://[^\\s<]+)");
    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.from:${spring.mail.username:}}")
    private String fromAddress;

    @Autowired
    private TcmEmailLogMapper emailLogMapper;

    @Autowired
    private TcmClinicSettingMapper clinicSettingMapper;

    @Autowired
    private HospitalFileStorage hospitalFileStorage;

    @Override
    public boolean sendAndLog(String to, String subject, String body, String type)
    {
        return sendAndLog(to, subject, body, type, null);
    }

    @Override
    public boolean sendAndLog(String to, String subject, String body, String type, List<Map<String, Object>> attachments)
    {
        return sendAndLogInternal(to, subject, body, type, null, attachments);
    }

    @Override
    public boolean sendTemplateAndLog(
            String to,
            String templateKey,
            Map<String, ?> variables,
            String fallbackSubject,
            String fallbackBody,
            String type)
    {
        return sendTemplateAndLog(to, templateKey, variables, fallbackSubject, fallbackBody, type, null);
    }

    @Override
    public boolean sendTemplateAndLog(
            String to,
            String templateKey,
            Map<String, ?> variables,
            String fallbackSubject,
            String fallbackBody,
            String type,
            List<Map<String, Object>> attachments)
    {
        EmailTemplateRegistry.RenderedEmail rendered = EmailTemplateRegistry.render(
                getSettingValue("emailTemplates"),
                templateKey,
                variables,
                fallbackSubject,
                fallbackBody);
        Map<String, Object> payloadExtras = new LinkedHashMap<String, Object>();
        payloadExtras.put("templateKey", rendered.getTemplateKey());
        payloadExtras.put("variables", variables != null ? variables : new LinkedHashMap<String, Object>());
        return sendAndLogInternal(to, rendered.getSubject(), rendered.getBody(), type, payloadExtras, attachments);
    }

    private boolean sendAndLogInternal(
            String to,
            String subject,
            String body,
            String type,
            Map<String, Object> payloadExtras,
            List<Map<String, Object>> attachments)
    {
        boolean sent = false;
        String sentAt = null;
        String deliveryError = null;
        String safeSubject = subject != null ? subject : "";
        List<EmailAttachment> resolvedAttachments = resolveAttachments(attachments);
        boolean requestedAttachments = attachments != null && !attachments.isEmpty();
        boolean attachmentError = requestedAttachments && resolvedAttachments.size() != attachments.size();
        String safeBody = prepareBodyForAttachments(body != null ? body : "", type, !resolvedAttachments.isEmpty());
        String htmlBody = buildHtmlEmail(safeBody);

        // Try real delivery.
        if (attachmentError)
        {
            deliveryError = "Email attachment could not be resolved.";
            log.warn("邮件未发送，附件无法解析: to={}, subject={}", to, safeSubject);
        }
        else if (mailSender != null && to != null && !to.isEmpty())
        {
            try
            {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                if (fromAddress != null && !fromAddress.isEmpty())
                {
                    helper.setFrom(fromAddress);
                }
                helper.setTo(to);
                helper.setSubject(safeSubject);
                helper.setText(toPlainText(safeBody), htmlBody);
                for (EmailAttachment attachment : resolvedAttachments)
                {
                    helper.addAttachment(attachment.fileName, new FileSystemResource(attachment.path.toFile()));
                }
                mailSender.send(message);
                sent = true;
                sentAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                log.info("邮件发送成功: to={}, subject={}, attachments={}",
                        to, safeSubject, resolvedAttachments.size());
            }
            catch (Exception e)
            {
                deliveryError = e.getMessage();
                log.warn("邮件发送失败: to={}, error={}", to, e.getMessage());
            }
        }
        else
        {
            deliveryError = "SMTP is not configured.";
            log.warn("邮件未发送，SMTP未配置: to={}, subject={}", to, safeSubject);
        }

        // 记录日志
        TcmEmailLog emailLog = new TcmEmailLog();
        emailLog.setToEmail(to);
        emailLog.setSubject(safeSubject);
        emailLog.setEmailType(type);
        emailLog.setBody(safeBody);
        emailLog.setSentAt(sentAt);
        JSONObject payload = new JSONObject();
        payload.put("sent", sent);
        payload.put("html", true);
        payload.put("attachmentError", attachmentError);
        payload.put("attachmentCount", resolvedAttachments.size());
        if (StringUtils.isNotBlank(deliveryError))
        {
            payload.put("deliveryError", deliveryError);
        }
        if (requestedAttachments)
        {
            // A retry must retain every requested file, including a currently missing one.
            // Otherwise a failed invoice could be resent successfully without its attachment.
            payload.put("attachments", attachmentError ? attachments : toAttachmentPayload(resolvedAttachments));
        }
        if (payloadExtras != null)
        {
            payload.putAll(payloadExtras);
        }
        emailLog.setPayload(JSON.toJSONString(payload));
        emailLogMapper.insertTcmEmailLog(emailLog);

        return sent;
    }

    private List<EmailAttachment> resolveAttachments(List<Map<String, Object>> attachments)
    {
        if (attachments == null || attachments.isEmpty())
        {
            return Collections.emptyList();
        }
        List<EmailAttachment> resolved = new ArrayList<EmailAttachment>();
        for (Map<String, Object> item : attachments)
        {
            if (item == null)
            {
                continue;
            }
            String resource = resolveAttachmentResource(item);
            if (StringUtils.isBlank(resource))
            {
                continue;
            }
            try
            {
                if (!FileUtils.checkAllowDownload(resource))
                {
                    log.warn("Email attachment blocked: {}", resource);
                    continue;
                }
                Path path = hospitalFileStorage.resolve(resource);
                if (!Files.exists(path) || !Files.isRegularFile(path))
                {
                    if (!hospitalFileStorage.restoreResource(resource))
                    {
                        log.warn("Email attachment missing: {}", resource);
                        continue;
                    }
                }
                String fileName = firstString(item, "fileName", "name");
                if (StringUtils.isBlank(fileName))
                {
                    fileName = path.getFileName().toString();
                }
                String contentType = firstString(item, "contentType", "mimeType");
                if (StringUtils.isBlank(contentType))
                {
                    contentType = Files.probeContentType(path);
                }
                resolved.add(new EmailAttachment(fileName, resource, path, contentType));
            }
            catch (Exception e)
            {
                log.warn("Email attachment ignored: resource={}, error={}", resource, e.getMessage());
            }
        }
        return resolved;
    }

    private List<Map<String, Object>> toAttachmentPayload(List<EmailAttachment> attachments)
    {
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
        for (EmailAttachment attachment : attachments)
        {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("fileName", attachment.fileName);
            item.put("resource", attachment.resource);
            item.put("contentType", StringUtils.defaultString(attachment.contentType));
            payload.add(item);
        }
        return payload;
    }

    private String prepareBodyForAttachments(String body, String type, boolean hasAttachments)
    {
        String safeBody = StringUtils.defaultString(body);
        if (!hasAttachments || !"invoice".equalsIgnoreCase(StringUtils.defaultString(type)))
        {
            return safeBody;
        }
        return stripPublicFileAccessLines(safeBody).trim();
    }

    private String stripPublicFileAccessLines(String body)
    {
        String[] lines = StringUtils.defaultString(body).split("\\R", -1);
        StringBuilder cleaned = new StringBuilder();
        for (String line : lines)
        {
            String lower = line.toLowerCase();
            if (line.contains("/api/public/files/access")
                    || line.contains("发票链接")
                    || lower.contains("invoice link"))
            {
                continue;
            }
            if (cleaned.length() > 0)
            {
                cleaned.append("\n");
            }
            cleaned.append(line);
        }
        return cleaned.toString().replaceAll("\\n{3,}", "\n\n");
    }

    private String firstString(Map<String, Object> source, String... keys)
    {
        for (String key : keys)
        {
            Object value = source.get(key);
            if (value != null)
            {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty())
                {
                    return text;
                }
            }
        }
        return "";
    }

    private String resolveAttachmentResource(Map<String, Object> item)
    {
        String resource = firstString(item,
                "resource", "attachmentResource", "filePath", "path", "invoicePdfPath", "reportPdfPath");
        if (StringUtils.isNotBlank(resource))
        {
            return resource;
        }
        return extractResourceFromAccessUrl(firstString(item,
                "url", "href", "fileUrl", "pdfUrl", "invoicePdfUrl", "reportPdfUrl"));
    }

    private String extractResourceFromAccessUrl(String value)
    {
        String text = StringUtils.defaultString(value).trim();
        if (StringUtils.isBlank(text))
        {
            return "";
        }
        if (text.startsWith(HospitalFileStorage.PRIVATE_PREFIX + "/"))
        {
            return text;
        }
        int queryIndex = text.indexOf('?');
        String query = queryIndex >= 0 ? text.substring(queryIndex + 1) : text;
        for (String part : query.split("&"))
        {
            int equalsIndex = part.indexOf('=');
            if (equalsIndex <= 0)
            {
                continue;
            }
            String key = decodeUrlPart(part.substring(0, equalsIndex));
            if (!"resource".equals(key))
            {
                continue;
            }
            return decodeUrlPart(part.substring(equalsIndex + 1));
        }
        return "";
    }

    private String decodeUrlPart(String value)
    {
        try
        {
            return URLDecoder.decode(StringUtils.defaultString(value), "UTF-8");
        }
        catch (Exception ignored)
        {
            return StringUtils.defaultString(value);
        }
    }

    private String buildHtmlEmail(String body)
    {
        String[] blocks = StringUtils.defaultString(body).split("\\n\\s*\\n");
        StringBuilder content = new StringBuilder();
        for (String block : blocks)
        {
            String trimmed = block.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            content.append("<p>")
                    .append(linkify(trimmed).replace("\n", "<br>"))
                    .append("</p>");
        }
        if (content.length() == 0)
        {
            content.append("<p></p>");
        }
        return "<!doctype html><html><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;background:#f5f7f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;color:#2f3437;\">"
                + "<div style=\"max-width:680px;margin:0 auto;padding:28px 16px;\">"
                + "<div style=\"background:#fff;border:1px solid #e5e7eb;border-radius:10px;overflow:hidden;box-shadow:0 8px 24px rgba(31,41,55,.06);\">"
                + "<div style=\"padding:18px 24px;background:#2d6a4f;color:#fff;font-size:18px;font-weight:700;letter-spacing:0;\">OTCM Acupuncture Clinic</div>"
                + "<div style=\"padding:24px;font-size:15px;line-height:1.7;\">"
                + content
                + "</div></div></div></body></html>";
    }

    private String linkify(String text)
    {
        Matcher matcher = WEB_URL_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
        int last = 0;
        while (matcher.find())
        {
            buffer.append(escapeHtml(text.substring(last, matcher.start())));
            String url = matcher.group(1);
            String escapedUrl = escapeHtml(url);
            buffer.append("<a href=\"")
                    .append(escapedUrl)
                    .append("\" style=\"color:#2d6a4f;text-decoration:underline;\">")
                    .append(escapedUrl)
                    .append("</a>");
            last = matcher.end();
        }
        buffer.append(escapeHtml(text.substring(last)));
        return buffer.toString();
    }

    private String escapeHtml(String value)
    {
        return StringUtils.defaultString(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String toPlainText(String body)
    {
        return StringUtils.defaultString(body);
    }

    private String getSettingValue(String key)
    {
        try
        {
            TcmClinicSetting setting = clinicSettingMapper.selectSettingByKey(key);
            return setting != null ? setting.getSettingValue() : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private static final class EmailAttachment
    {
        private final String fileName;
        private final String resource;
        private final Path path;
        private final String contentType;

        private EmailAttachment(String fileName, String resource, Path path, String contentType)
        {
            this.fileName = fileName;
            this.resource = resource;
            this.path = path;
            this.contentType = contentType;
        }
    }
}
