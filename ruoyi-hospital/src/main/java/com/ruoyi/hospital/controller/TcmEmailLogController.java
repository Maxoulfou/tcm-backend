package com.ruoyi.hospital.controller;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.net.URLDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.TcmEmailLog;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.service.ITcmEmailLogService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.service.ITcmPdfService;
import com.ruoyi.hospital.util.EmailTemplateRegistry;
import com.ruoyi.hospital.utils.PayloadUtils;

@RestController
@RequestMapping("/api/email-logs")
public class TcmEmailLogController
{
    @Autowired
    private ITcmEmailLogService emailLogService;

    @Autowired
    private ITcmEmailService emailService;

    @Autowired
    private ITcmPatientService patientService;

    @Autowired
    private ITcmPdfService pdfService;

    @PreAuthorize("@ss.hasRole('admin')")
    @GetMapping("")
    public List<TcmEmailLog> list()
    {
        return emailLogService.selectTcmEmailLogList(new TcmEmailLog());
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,cashier')")
    @PostMapping("")
    public Map<String, Object> create(@RequestBody Map<String, Object> body)
    {
        String to = stringValue(body.get("to"));
        if (to == null)
        {
            to = stringValue(body.get("toEmail"));
        }
        String subject = stringValue(body.get("subject"));
        String emailBody = resolveBody(body);
        Map<String, Object> variables = resolveVariables(body);
        if (subject != null && !variables.isEmpty())
        {
            subject = EmailTemplateRegistry.renderText(subject, variables);
        }
        String type = stringValue(body.get("type"));
        if (type == null)
        {
            type = stringValue(body.get("emailType"));
        }
        String templateKey = resolveTemplateKey(stringValue(body.get("templateKey")), type);
        boolean useTemplate = templateKey != null || Boolean.TRUE.equals(body.get("useTemplate"));
        List<Map<String, Object>> attachments = resolveAttachments(body);
        to = resolveLatestPatientRecipient(to, type, templateKey, variables);

        boolean sent = useTemplate
                ? emailService.sendTemplateAndLog(to, templateKey, variables, subject, emailBody, type, attachments)
                : emailService.sendAndLog(to, subject, emailBody, type, attachments);

        Map<String, Object> result = new HashMap<>();
        result.put("success", sent);
        result.put("to", to);
        result.put("subject", subject);
        result.put("templateKey", templateKey);
        return result;
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,cashier')")
    @PostMapping("/{id}/resend")
    public Map<String, Object> resend(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        TcmEmailLog source = emailLogService.selectTcmEmailLogById(id);
        if (source == null)
        {
            throw new ServiceException("email log not found");
        }
        Map<String, Object> overrides = body != null ? body : new HashMap<>();
        String to = stringValue(overrides.get("to"));
        if (to == null)
        {
            to = stringValue(overrides.get("toEmail"));
        }
        if (to == null)
        {
            to = source.getToEmail();
        }
        String subject = stringValue(overrides.get("subject"));
        if (subject == null)
        {
            subject = source.getSubject();
        }
        String emailBody = resolveBody(overrides);
        if (emailBody == null)
        {
            emailBody = source.getBody();
        }
        String type = stringValue(overrides.get("type"));
        if (type == null)
        {
            type = stringValue(overrides.get("emailType"));
        }
        if (type == null)
        {
            type = source.getEmailType();
        }
        JSONObject payload = parsePayload(source.getPayload());
        String templateKey = stringValue(overrides.get("templateKey"));
        if (templateKey == null)
        {
            templateKey = stringValue(payload.get("templateKey"));
        }
        templateKey = resolveTemplateKey(templateKey, type);
        Map<String, Object> variables = resolveVariables(overrides);
        if (variables.isEmpty() && payload.get("variables") instanceof Map<?, ?>)
        {
            variables = resolveVariables(payload);
        }
        List<Map<String, Object>> attachments = resolveAttachments(overrides);
        if (attachments.isEmpty())
        {
            attachments = resolveAttachments(payload);
        }
        if (payload.getBooleanValue("invoiceGenerationFailed") && attachments.isEmpty())
        {
            String consultationId = stringValue(payload.get("invoiceConsultationId"));
            if (consultationId == null)
            {
                throw new ServiceException("The invoice's consultation is missing; generate the invoice from the consultation first.");
            }
            Map<String, String> invoice = pdfService.generateInvoice(consultationId);
            String resource = invoice != null ? stringValue(invoice.get("resource")) : null;
            if (resource == null && invoice != null) resource = stringValue(invoice.get("filePath"));
            if (resource == null)
            {
                throw new ServiceException("Invoice PDF generation returned no attachment. Please retry.");
            }
            Map<String, Object> attachment = new HashMap<>();
            attachment.put("resource", resource);
            attachment.put("fileName", "invoice.pdf");
            attachments.add(attachment);
        }
        if (payload.getBooleanValue("attachmentError") && attachments.isEmpty())
        {
            throw new ServiceException("The original attachment is unavailable. Attach the file before resending.");
        }
        boolean useTemplate = templateKey != null || Boolean.TRUE.equals(overrides.get("useTemplate"));
        to = resolveLatestPatientRecipient(to, type, templateKey, variables);
        boolean sent = useTemplate
                ? emailService.sendTemplateAndLog(to, templateKey, variables, subject, emailBody, type, attachments)
                : emailService.sendAndLog(to, subject, emailBody, type, attachments);
        Map<String, Object> result = new HashMap<>();
        result.put("success", sent);
        result.put("to", to);
        result.put("subject", subject);
        result.put("type", type);
        result.put("templateKey", templateKey);
        result.put("sourceLogId", id);
        return result;
    }

    private String resolveLatestPatientRecipient(
            String requestedTo,
            String type,
            String templateKey,
            Map<String, Object> variables)
    {
        if (!isPatientFacingEmail(type, templateKey))
        {
            return requestedTo;
        }
        String patientId = variables != null ? stringValue(variables.get("patientId")) : null;
        TcmPatient patient = null;
        if (patientId != null)
        {
            patient = patientService.selectTcmPatientById(patientId);
        }
        if (patient == null)
        {
            patient = findPatientByKnownEmail(requestedTo);
        }
        String latestEmail = resolvePrimaryEmail(patient);
        return latestEmail != null ? latestEmail : requestedTo;
    }

    private String resolveTemplateKey(String templateKey, String type)
    {
        String canonical = EmailTemplateRegistry.canonicalKey(templateKey);
        if (canonical != null && !canonical.isEmpty())
        {
            return canonical;
        }
        canonical = EmailTemplateRegistry.canonicalKey(type);
        return canonical != null && !canonical.isEmpty() ? canonical : templateKey;
    }

    private boolean isPatientFacingEmail(String type, String templateKey)
    {
        String normalizedType = type != null ? type.trim().toLowerCase() : "";
        if (normalizedType.contains("internal"))
        {
            return false;
        }
        String canonicalTemplateKey = EmailTemplateRegistry.canonicalKey(templateKey);
        return canonicalTemplateKey == null || !canonicalTemplateKey.toLowerCase().startsWith("internal");
    }

    private String resolvePrimaryEmail(TcmPatient patient)
    {
        if (patient == null)
        {
            return null;
        }
        String primary = stringValue(patient.getEmail());
        if (primary != null)
        {
            return primary;
        }
        Map<String, Object> flattened = PayloadUtils.flatten(patient);
        Object emails = flattened.get("emails");
        if (emails instanceof List<?>)
        {
            for (Object value : (List<?>) emails)
            {
                String email = stringValue(value);
                if (email != null)
                {
                    return email;
                }
            }
        }
        return null;
    }

    private TcmPatient findPatientByKnownEmail(String email)
    {
        String normalizedEmail = stringValue(email);
        if (normalizedEmail == null)
        {
            return null;
        }
        List<TcmPatient> patients = patientService.selectTcmPatientList(new TcmPatient());
        if (patients == null)
        {
            return null;
        }
        for (TcmPatient patient : patients)
        {
            if (hasKnownEmail(patient, normalizedEmail))
            {
                return patient;
            }
        }
        return null;
    }

    private boolean hasKnownEmail(TcmPatient patient, String email)
    {
        if (patient == null || email == null)
        {
            return false;
        }
        String primary = stringValue(patient.getEmail());
        if (primary != null && primary.equalsIgnoreCase(email))
        {
            return true;
        }
        Map<String, Object> flattened = PayloadUtils.flatten(patient);
        Object emails = flattened.get("emails");
        if (emails instanceof List<?>)
        {
            for (Object value : (List<?>) emails)
            {
                String knownEmail = stringValue(value);
                if (knownEmail != null && knownEmail.equalsIgnoreCase(email))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private String resolveBody(Map<String, Object> body)
    {
        String direct = stringValue(body.get("body"));
        if (direct != null)
        {
            return direct;
        }
        String template = stringValue(body.get("template"));
        if (template == null)
        {
            template = stringValue(body.get("templateBody"));
        }
        if (template == null)
        {
            return null;
        }
        Object variables = body.get("variables");
        if (variables instanceof Map<?, ?>)
        {
            return EmailTemplateRegistry.renderText(template, resolveVariables(body));
        }
        return template;
    }

    private Map<String, Object> resolveVariables(Map<String, Object> body)
    {
        Map<String, Object> variables = new HashMap<>();
        Object source = body.get("variables");
        if (source instanceof Map<?, ?>)
        {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) source).entrySet())
            {
                if (entry.getKey() != null)
                {
                    variables.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return variables;
    }

    private List<Map<String, Object>> resolveAttachments(Map<String, Object> body)
    {
        List<Map<String, Object>> attachments = new ArrayList<>();
        Object source = body.get("attachments");
        if (source instanceof List<?>)
        {
            for (Object item : (List<?>) source)
            {
                if (item instanceof Map<?, ?>)
                {
                    Map<String, Object> attachment = new HashMap<>();
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet())
                    {
                        if (entry.getKey() != null)
                        {
                            attachment.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                    }
                    attachments.add(attachment);
                }
            }
        }
        String resource = firstString(body, "attachmentResource", "invoicePdfPath", "reportPdfPath", "filePath");
        if (resource == null)
        {
            resource = extractResourceFromAccessUrl(firstString(body,
                    "attachmentUrl", "invoicePdfUrl", "reportPdfUrl", "pdfUrl", "url"));
        }
        if (resource != null)
        {
            Map<String, Object> attachment = new HashMap<>();
            attachment.put("resource", resource);
            attachment.put("fileName", firstString(body, "attachmentFileName", "fileName"));
            attachments.add(attachment);
        }
        return attachments;
    }

    private String firstString(Map<String, Object> body, String... keys)
    {
        for (String key : keys)
        {
            String value = stringValue(body.get(key));
            if (value != null)
            {
                return value;
            }
        }
        return null;
    }

    private String extractResourceFromAccessUrl(String value)
    {
        String text = stringValue(value);
        if (text == null)
        {
            return null;
        }
        if (text.startsWith("hospital-private/"))
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
            if ("resource".equals(key))
            {
                return stringValue(decodeUrlPart(part.substring(equalsIndex + 1)));
            }
        }
        return null;
    }

    private String decodeUrlPart(String value)
    {
        try
        {
            return URLDecoder.decode(String.valueOf(value), "UTF-8");
        }
        catch (Exception ignored)
        {
            return String.valueOf(value);
        }
    }

    private String stringValue(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private JSONObject parsePayload(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return new JSONObject();
        }
        try
        {
            return JSON.parseObject(value);
        }
        catch (Exception ignored)
        {
            return new JSONObject();
        }
    }
}
