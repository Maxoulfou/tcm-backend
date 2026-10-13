package com.ruoyi.hospital.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmEmailLog;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmEmailLogService;
import com.ruoyi.hospital.service.ITcmPdfService;
import com.ruoyi.hospital.service.ITcmSettingsService;
import com.ruoyi.hospital.service.ITcmStripePaymentService;
import com.ruoyi.hospital.service.ProviderPaymentResult;
import com.ruoyi.hospital.utils.PayloadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class TcmStripePaymentServiceImpl implements ITcmStripePaymentService
{
    private static final Logger log = LoggerFactory.getLogger(TcmStripePaymentServiceImpl.class);
    private static final String DEFAULT_CLINIC_NAME = "OTCM Acupuncture Clinic";
    private static final String STRIPE_API_VERSION = "2026-02-25.clover";
    private static final Set<String> SUPPORTED_WEBHOOK_EVENTS = new HashSet<>(Arrays.asList(
            "payment_intent.succeeded",
            "payment_intent.payment_failed",
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
            "charge.refunded",
            "refund.created",
            "terminal.reader.action_succeeded",
            "terminal.reader.action_failed"));

    @Autowired
    private ITcmConsultationService consultationService;

    @Autowired
    private TcmPatientMapper patientMapper;

    @Autowired
    private ITcmEmailService emailService;

    @Autowired
    private ITcmEmailLogService emailLogService;

    @Autowired(required = false)
    private ITcmPdfService pdfService;

    @Autowired
    private ITcmSettingsService settingsService;

    @Autowired(required = false)
    @Qualifier("threadPoolTaskExecutor")
    private TaskExecutor stripeWebhookTaskExecutor = Runnable::run;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    @Value("${stripe.terminal-reader-id:}")
    private String stripeTerminalReaderId;

    @Value("${stripe.publishable-key:}")
    private String stripePublishableKey;

    @Value("${public.app-base-url:${PUBLIC_APP_BASE_URL:http://127.0.0.1:5173}}")
    private String publicAppBaseUrl;

    @Value("${tcm.instance-id:}")
    private String instanceId;

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate()
    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    private String configuredStripeSecretKey()
    {
        String value = settingsService != null ? settingsService.getStripeSecretKey() : "";
        return StringUtils.defaultIfBlank(value, stripeSecretKey);
    }

    private String configuredStripeWebhookSecret()
    {
        String value = settingsService != null ? settingsService.getStripeWebhookSecret() : "";
        return StringUtils.defaultIfBlank(value, stripeWebhookSecret);
    }

    private String configuredStripeTerminalReaderId()
    {
        String value = settingsService != null ? settingsService.getStripeTerminalReaderId() : "";
        return StringUtils.defaultIfBlank(value, stripeTerminalReaderId);
    }

    private String configuredStripePublishableKey()
    {
        String value = settingsService != null ? settingsService.getStripePublishableKey() : "";
        return StringUtils.defaultIfBlank(value, stripePublishableKey);
    }

    @Override
    public Map<String, Object> createCheckoutSession(String consultationId)
    {
        String secretKey = configuredStripeSecretKey();
        if (StringUtils.isBlank(secretKey))
        {
            throw new ServiceException("Stripe secret key is not configured");
        }
        TcmConsultation consultation = consultationService.selectTcmConsultationById(consultationId);
        if (consultation == null)
        {
            throw new ServiceException("consultation not found");
        }
        if ("draft".equals(consultation.getStatus()))
        {
            throw new ServiceException("草稿问诊不能创建 Stripe 支付");
        }
        JSONObject payload = parsePayload(consultation.getPayload());
        BigDecimal outstanding = getOutstandingAmount(payload);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("当前没有可收款金额");
        }

        String currency = defaultText(payload.getString("currency"), "CAD").toLowerCase();
        long unitAmount = outstanding.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
        TcmPatient patient = patientMapper.selectTcmPatientById(consultation.getPatientId());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("success_url", buildReturnUrl("stripe=success&session_id={CHECKOUT_SESSION_ID}"));
        form.add("cancel_url", buildReturnUrl("stripe=cancelled"));
        form.add("client_reference_id", consultation.getId());
        form.add("metadata[consultationId]", consultation.getId());
        form.add("metadata[consultationNo]", defaultText(consultation.getConsultationId(), consultation.getId()));
        addInstanceMetadata(form);
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", currency);
        form.add("line_items[0][price_data][unit_amount]", String.valueOf(unitAmount));
        form.add("line_items[0][price_data][product_data][name]", "Invoice " + defaultText(consultation.getConsultationId(), consultation.getId()));
        if (patient != null && StringUtils.isNotBlank(patient.getEmail()))
        {
            form.add("customer_email", patient.getEmail().trim());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(secretKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add("Stripe-Version", STRIPE_API_VERSION);
        JSONObject response = restTemplate.postForObject(
                "https://api.stripe.com/v1/checkout/sessions",
                new HttpEntity<>(form, headers),
                JSONObject.class);
        if (response == null || StringUtils.isBlank(response.getString("url")))
        {
            throw new ServiceException("Stripe checkout session was not returned");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", response.getString("id"));
        result.put("url", response.getString("url"));
        return result;
    }

    @Override
    public Map<String, Object> createTerminalPayment(String consultationId)
    {
        String secretKey = configuredStripeSecretKey();
        String readerId = configuredStripeTerminalReaderId();
        if (StringUtils.isBlank(secretKey))
        {
            throw new ServiceException("Stripe secret key is not configured");
        }
        if (StringUtils.isBlank(readerId))
        {
            throw new ServiceException("Stripe Terminal reader ID is not configured");
        }
        TcmConsultation consultation = consultationService.selectTcmConsultationById(consultationId);
        if (consultation == null)
        {
            throw new ServiceException("consultation not found");
        }
        if ("draft".equals(consultation.getStatus()))
        {
            throw new ServiceException("草稿问诊不能创建 Stripe POS 支付");
        }
        JSONObject payload = parsePayload(consultation.getPayload());
        BigDecimal outstanding = getOutstandingAmount(payload);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("当前没有可收款金额");
        }

        JSONObject paymentIntent = createTerminalPaymentIntent(consultation, payload, outstanding);
        String paymentIntentId = paymentIntent.getString("id");
        JSONObject reader = processPaymentOnReader(paymentIntentId);

        Map<String, Object> result = buildTerminalStatusResult(consultationId, paymentIntent, reader);
        result.put("readerId", readerId);
        result.put("publishableKeyConfigured", StringUtils.isNotBlank(configuredStripePublishableKey()));
        return result;
    }

    @Override
    public Map<String, Object> getTerminalPaymentStatus(String consultationId, String paymentIntentId)
    {
        String secretKey = configuredStripeSecretKey();
        String readerId = configuredStripeTerminalReaderId();
        if (StringUtils.isBlank(secretKey))
        {
            throw new ServiceException("Stripe secret key is not configured");
        }
        if (StringUtils.isBlank(readerId))
        {
            throw new ServiceException("Stripe Terminal reader ID is not configured");
        }
        if (StringUtils.isBlank(paymentIntentId) || "null".equals(paymentIntentId))
        {
            throw new ServiceException("paymentIntentId is required");
        }

        JSONObject paymentIntent = retrievePaymentIntent(paymentIntentId);
        requireOwnedPaymentIntent(consultationId, paymentIntent);
        if ("requires_capture".equals(paymentIntent.getString("status")))
        {
            paymentIntent = capturePaymentIntent(paymentIntentId);
        }
        JSONObject reader = retrieveReader(readerId);
        Map<String, Object> result = buildTerminalStatusResult(consultationId, paymentIntent, reader);

        if ("succeeded".equals(paymentIntent.getString("status")))
        {
            TcmConsultation recorded = recordTerminalPaymentIfNeeded(consultationId, paymentIntent);
            if (recorded != null)
            {
                result.put("consultation", PayloadUtils.flatten(recorded));
            }
            result.put("paid", true);
        }
        return result;
    }

    private JSONObject createTerminalPaymentIntent(TcmConsultation consultation, JSONObject payload, BigDecimal outstanding)
    {
        String currency = defaultText(payload.getString("currency"), "CAD").toLowerCase();
        long amount = outstanding.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", String.valueOf(amount));
        form.add("currency", currency);
        form.add("payment_method_types[]", "card_present");
        form.add("metadata[consultationId]", consultation.getId());
        form.add("metadata[consultationNo]", defaultText(consultation.getConsultationId(), consultation.getId()));
        addInstanceMetadata(form);
        form.add("description", "OTCM POS payment " + defaultText(consultation.getConsultationId(), consultation.getId()));
        return postStripeForm("https://api.stripe.com/v1/payment_intents", form);
    }

    private JSONObject processPaymentOnReader(String paymentIntentId)
    {
        String readerId = configuredStripeTerminalReaderId();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", paymentIntentId);
        return postStripeForm(
                "https://api.stripe.com/v1/terminal/readers/" + urlEncode(readerId) + "/process_payment_intent",
                form);
    }

    private JSONObject retrievePaymentIntent(String paymentIntentId)
    {
        return getStripeObject("https://api.stripe.com/v1/payment_intents/" + urlEncode(paymentIntentId));
    }

    private JSONObject capturePaymentIntent(String paymentIntentId)
    {
        return postStripeForm(
                "https://api.stripe.com/v1/payment_intents/" + urlEncode(paymentIntentId) + "/capture",
                new LinkedMultiValueMap<String, String>());
    }

    private JSONObject retrieveReader(String readerId)
    {
        return getStripeObject("https://api.stripe.com/v1/terminal/readers/" + urlEncode(readerId));
    }

    private Map<String, Object> buildTerminalStatusResult(String consultationId, JSONObject paymentIntent, JSONObject reader)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consultationId", consultationId);
        result.put("paymentIntentId", paymentIntent != null ? paymentIntent.getString("id") : "");
        result.put("paymentIntentStatus", paymentIntent != null ? paymentIntent.getString("status") : "");
        result.put("readerId", reader != null ? reader.getString("id") : configuredStripeTerminalReaderId());
        JSONObject action = reader != null ? reader.getJSONObject("action") : null;
        result.put("readerStatus", reader != null ? reader.getString("status") : "");
        result.put("actionStatus", action != null ? action.getString("status") : "");
        result.put("actionType", action != null ? action.getString("type") : "");
        result.put("paid", paymentIntent != null && "succeeded".equals(paymentIntent.getString("status")));
        result.put("amount", stripeAmountToMoney(paymentIntent != null ? paymentIntent.getLongValue("amount") : 0));
        result.put("amountReceived", stripeAmountToMoney(paymentIntent != null ? paymentIntent.getLongValue("amount_received") : 0));
        result.put("currency", paymentIntent != null ? defaultText(paymentIntent.getString("currency"), "CAD").toUpperCase() : "CAD");
        return result;
    }

    private TcmConsultation recordTerminalPaymentIfNeeded(String consultationId, JSONObject paymentIntent)
    {
        requireOwnedPaymentIntent(consultationId, paymentIntent);
        JSONObject metadata = paymentIntent.getJSONObject("metadata");
        String metadataConsultationId = metadata != null ? metadata.getString("consultationId") : "";
        if (StringUtils.isNotBlank(metadataConsultationId) && !metadataConsultationId.equals(consultationId))
        {
            throw new ServiceException("Stripe payment intent does not belong to this consultation");
        }

        String paymentIntentId = paymentIntent.getString("id");
        BigDecimal amount = stripeAmountToMoney(paymentIntent.getLongValue("amount_received"));
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            amount = stripeAmountToMoney(paymentIntent.getLongValue("amount"));
        }

        Map<String, Object> paymentInfo = new HashMap<>();
        paymentInfo.put("provider", "stripe_terminal");
        paymentInfo.put("paymentMethod", "bankcard");
        paymentInfo.put("amount", amount);
        paymentInfo.put("currency", defaultText(paymentIntent.getString("currency"), "CAD").toUpperCase());
        paymentInfo.put("stripePaymentIntentId", paymentIntentId);
        paymentInfo.put("stripeReaderId", configuredStripeTerminalReaderId());
        paymentInfo.put("providerStatus", paymentIntent.getString("status"));
        paymentInfo.put("livemode", paymentIntent.getBooleanValue("livemode"));

        ProviderPaymentResult payment = consultationService.recordProviderPayment(consultationId, "stripe_terminal", paymentInfo);
        TcmConsultation recorded = payment.getConsultation();
        if (payment.isNewlyRecorded())
        {
            dispatchInvoiceEmail(recorded, paymentInfo);
        }
        return recorded;
    }

    private JSONObject postStripeForm(String url, MultiValueMap<String, String> form)
    {
        return restTemplate.postForObject(url, new HttpEntity<>(form, buildStripeFormHeaders()), JSONObject.class);
    }

    protected JSONObject getStripeObject(String url)
    {
        ResponseEntity<JSONObject> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(buildStripeJsonHeaders()),
                JSONObject.class);
        return response.getBody();
    }

    private HttpHeaders buildStripeFormHeaders()
    {
        HttpHeaders headers = buildStripeJsonHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    private HttpHeaders buildStripeJsonHeaders()
    {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(configuredStripeSecretKey());
        headers.add("Stripe-Version", STRIPE_API_VERSION);
        return headers;
    }

    private BigDecimal stripeAmountToMoney(long stripeAmount)
    {
        return BigDecimal.valueOf(stripeAmount).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private String urlEncode(String value)
    {
        try
        {
            return URLEncoder.encode(defaultText(value, ""), StandardCharsets.UTF_8.name());
        }
        catch (UnsupportedEncodingException e)
        {
            throw new ServiceException("URL encoding failed");
        }
    }

    @Override
    public Map<String, Object> handleWebhook(String payload, String signatureHeader)
    {
        String verifiedPayload = payload;
        String webhookSecret = configuredStripeWebhookSecret();
        if (StringUtils.isBlank(webhookSecret))
        {
            throw new ServiceException("Stripe webhook secret is not configured");
        }
        try
        {
            verifySignature(payload, signatureHeader, webhookSecret);
        }
        catch (ServiceException e)
        {
            if (!isSignatureMismatch(e))
            {
                throw e;
            }
            verifiedPayload = fetchStripeEventPayloadForSignatureFallback(payload, e);
        }
        JSONObject event = parsePayload(verifiedPayload);
        String type = event.getString("type");
        if (!SUPPORTED_WEBHOOK_EVENTS.contains(type))
        {
            return ok(false, type);
        }

        JSONObject eventData = event.getJSONObject("data");
        JSONObject eventObject = eventData != null ? eventData.getJSONObject("object") : null;
        if (!belongsToInstance(eventObject))
        {
            return ok(false, type);
        }

        if ("payment_intent.succeeded".equals(type))
        {
            JSONObject data = event.getJSONObject("data");
            JSONObject paymentIntent = data != null ? data.getJSONObject("object") : null;
            if (paymentIntent == null)
            {
                throw new ServiceException("Invalid Stripe payment_intent webhook payload");
            }
            if (!"succeeded".equals(paymentIntent.getString("status")))
            {
                return ok(false, type);
            }
            JSONObject metadata = paymentIntent.getJSONObject("metadata");
            String consultationId = metadata != null ? metadata.getString("consultationId") : "";
            if (StringUtils.isBlank(consultationId))
            {
                return ok(false, type);
            }
            TcmConsultation recorded = recordTerminalPaymentIfNeeded(consultationId, paymentIntent);
            return ok(recorded != null, type);
        }

        if (!"checkout.session.completed".equals(type)
                && !"checkout.session.async_payment_succeeded".equals(type))
        {
            return ok(false, type);
        }

        JSONObject data = event.getJSONObject("data");
        JSONObject session = data != null ? data.getJSONObject("object") : null;
        if (session == null)
        {
            throw new ServiceException("Invalid Stripe webhook payload");
        }
        // Checkout completion can precede settlement for delayed payment methods.
        if (!"paid".equals(session.getString("payment_status")))
        {
            return ok(false, type);
        }
        String consultationId = null;
        JSONObject metadata = session.getJSONObject("metadata");
        if (metadata != null)
        {
            consultationId = metadata.getString("consultationId");
        }
        if (StringUtils.isBlank(consultationId))
        {
            consultationId = session.getString("client_reference_id");
        }
        if (StringUtils.isBlank(consultationId))
        {
            throw new ServiceException("Stripe session missing consultationId");
        }

        Map<String, Object> paymentInfo = new HashMap<>();
        paymentInfo.put("provider", "stripe");
        paymentInfo.put("paymentMethod", "card");
        paymentInfo.put("amount", BigDecimal.valueOf(session.getLongValue("amount_total")).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        paymentInfo.put("currency", StringUtils.defaultIfBlank(session.getString("currency"), "CAD").toUpperCase());
        paymentInfo.put("stripeSessionId", session.getString("id"));
        paymentInfo.put("stripePaymentIntentId", session.getString("payment_intent"));
        paymentInfo.put("stripeEventId", event.getString("id"));
        paymentInfo.put("providerStatus", session.getString("payment_status"));
        paymentInfo.put("livemode", event.getBooleanValue("livemode"));
        ProviderPaymentResult payment = consultationService.recordProviderPayment(consultationId, "stripe", paymentInfo);
        TcmConsultation recorded = payment.getConsultation();
        if (payment.isNewlyRecorded())
        {
            dispatchInvoiceEmail(recorded, paymentInfo);
        }
        return ok(true, type);
    }

    private boolean isSignatureMismatch(ServiceException e)
    {
        String message = e != null ? e.getMessage() : "";
        return "Invalid Stripe webhook signature".equals(message)
                || "Invalid Stripe-Signature".equals(message);
    }

    private void addInstanceMetadata(MultiValueMap<String, String> form)
    {
        if (StringUtils.isNotBlank(instanceId))
        {
            form.add("metadata[tcmInstance]", instanceId.trim());
        }
    }

    private boolean belongsToInstance(JSONObject stripeObject)
    {
        JSONObject metadata = stripeObject != null ? stripeObject.getJSONObject("metadata") : null;
        String owner = metadata != null ? metadata.getString("tcmInstance") : "";
        return defaultText(instanceId, "").equals(defaultText(owner, ""));
    }

    private void requireOwnedPaymentIntent(String consultationId, JSONObject paymentIntent)
    {
        JSONObject metadata = paymentIntent != null ? paymentIntent.getJSONObject("metadata") : null;
        String ownerConsultation = metadata != null ? metadata.getString("consultationId") : "";
        if (!belongsToInstance(paymentIntent) || !consultationId.equals(ownerConsultation))
        {
            throw new ServiceException("Stripe payment intent does not belong to this consultation and site");
        }
    }

    private String fetchStripeEventPayloadForSignatureFallback(String payload, ServiceException signatureError)
    {
        JSONObject untrustedEvent = parsePayload(payload);
        String eventId = untrustedEvent.getString("id");
        if (StringUtils.isBlank(eventId) || !eventId.startsWith("evt_"))
        {
            throw signatureError;
        }
        if (StringUtils.isBlank(configuredStripeSecretKey()))
        {
            throw signatureError;
        }
        try
        {
            JSONObject stripeEvent = getStripeObject("https://api.stripe.com/v1/events/" + urlEncode(eventId));
            if (stripeEvent == null || !eventId.equals(stripeEvent.getString("id")))
            {
                throw signatureError;
            }
            log.warn("Stripe webhook signature verification failed; verified event {} with Stripe API fallback", eventId);
            return JSON.toJSONString(stripeEvent);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.warn("Stripe webhook signature fallback failed for eventId={}: {}", eventId, e.getMessage());
            throw signatureError;
        }
    }
    private void dispatchInvoiceEmail(TcmConsultation consultation, Map<String, Object> paymentInfo)
    {
        try
        {
            stripeWebhookTaskExecutor.execute(() -> sendInvoiceEmail(consultation, paymentInfo));
        }
        catch (RejectedExecutionException e)
        {
            log.error("Stripe invoice task rejected after payment commit: consultationId={}",
                    consultation != null ? consultation.getId() : "", e);
            try
            {
                TcmPatient patient = consultation != null
                        ? patientMapper.selectTcmPatientById(consultation.getPatientId()) : null;
                if (patient != null && StringUtils.isNotBlank(patient.getEmail()))
                {
                    recordInvoicePreparationFailure(consultation, patient,
                            buildInvoiceEmailVariables(consultation, patient, paymentInfo), e);
                }
            }
            catch (Exception logError)
            {
                log.error("Could not persist failed Stripe invoice task", logError);
            }
        }
    }

    private void sendInvoiceEmail(TcmConsultation consultation, Map<String, Object> paymentInfo)
    {
        try
        {
            if (consultation == null || StringUtils.isBlank(consultation.getPatientId()))
            {
                return;
            }
            TcmPatient patient = patientMapper.selectTcmPatientById(consultation.getPatientId());
            if (patient == null || StringUtils.isBlank(patient.getEmail()))
            {
                // The invoice is still available in the patient's file list without an email address.
                generateInvoicePdf(consultation);
                return;
            }
            Map<String, Object> variables = buildInvoiceEmailVariables(consultation, patient, paymentInfo);
            Map<String, String> invoicePdf;
            try
            {
                // PDF generation (including remote backup) belongs after the payment commit.
                invoicePdf = generateInvoicePdf(consultation);
            }
            catch (Exception preparationError)
            {
                recordInvoicePreparationFailure(consultation, patient, variables, preparationError);
                return;
            }

            emailService.sendTemplateAndLog(
                    patient.getEmail(),
                    "invoice",
                    variables,
                    invoiceSubject(variables),
                    buildInvoiceFallbackBody(variables),
                    "invoice",
                    buildInvoiceAttachments(consultation, invoicePdf, parsePayload(consultation.getPayload())));
        }
        catch (Exception e)
        {
            log.warn("Stripe付款后发票邮件发送失败: consultationId={}, error={}",
                    consultation != null ? consultation.getId() : "", e.getMessage(), e);
        }
    }

    private Map<String, Object> buildInvoiceEmailVariables(TcmConsultation consultation, TcmPatient patient,
            Map<String, Object> paymentInfo)
    {
        JSONObject payload = parsePayload(consultation.getPayload());
        String consultationDate = defaultText(consultation.getConsultDate(), "");
        String currency = defaultText(stringValue(paymentInfo.get("currency")), defaultText(payload.getString("currency"), "CAD"));
        BigDecimal amount = toBigDecimal(paymentInfo.get("amount"));
        if (amount.compareTo(BigDecimal.ZERO) <= 0) amount = toBigDecimal(payload.get("totalAmount"));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("clinicName", normalizeClinicName(payload.getString("clinicName")));
        variables.put("patientId", defaultText(patient.getId(), ""));
        variables.put("patientName", defaultText(resolvePatientFirstName(patient), "Patient"));
        variables.put("patientEmail", defaultText(patient.getEmail(), ""));
        variables.put("consultationId", defaultText(consultation.getConsultationId(), consultation.getId()));
        variables.put("consultationDate", consultationDate);
        variables.put("appointmentDate", resolveAppointmentDate(consultationDate));
        variables.put("amount", currency.toUpperCase() + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        variables.put("invoiceLink", "");
        return variables;
    }

    private String invoiceSubject(Map<String, Object> variables)
    {
        return stringValue(variables.get("clinicName")) + "｜发票 "
                + defaultText(stringValue(variables.get("appointmentDate")), "");
    }

    private void recordInvoicePreparationFailure(TcmConsultation consultation, TcmPatient patient,
            Map<String, Object> variables, Exception error)
    {
        TcmEmailLog failed = new TcmEmailLog();
        failed.setToEmail(patient.getEmail());
        failed.setSubject(invoiceSubject(variables));
        failed.setBody(buildInvoiceFallbackBody(variables));
        failed.setEmailType("invoice");
        JSONObject payload = new JSONObject();
        payload.put("sent", false);
        payload.put("attachmentError", true);
        payload.put("attachmentCount", 0);
        payload.put("templateKey", "invoice");
        payload.put("variables", variables);
        payload.put("invoiceGenerationFailed", true);
        payload.put("invoiceConsultationId", consultation.getId());
        payload.put("deliveryError", "Invoice PDF is not ready. Use Resend to regenerate it: "
                + defaultText(error.getMessage(), error.getClass().getSimpleName()));
        failed.setPayload(payload.toJSONString());
        if (emailLogService.insertTcmEmailLog(failed) != 1)
        {
            throw new ServiceException("Could not persist failed invoice delivery");
        }
    }

    private Map<String, String> generateInvoicePdf(TcmConsultation consultation)
    {
        if (pdfService == null || consultation == null || StringUtils.isBlank(consultation.getId()))
        {
            throw new ServiceException("Invoice PDF service is unavailable");
        }
        Map<String, String> result = pdfService.generateInvoice(consultation.getId());
        if (result == null || StringUtils.isBlank(defaultText(result.get("resource"), result.get("filePath"))))
        {
            throw new ServiceException("Invoice PDF generation returned no attachment");
        }
        return result;
    }

    private String resolvePatientFirstName(TcmPatient patient)
    {
        if (patient == null)
        {
            return "";
        }
        String firstName = patient.getFirstName() != null ? patient.getFirstName().trim() : "";
        if (StringUtils.isNotBlank(firstName))
        {
            return firstName;
        }
        String name = patient.getName() != null ? patient.getName().trim() : "";
        if (StringUtils.isBlank(name))
        {
            return "";
        }
        String[] parts = name.split("\\s+");
        return parts.length >= 2 ? parts[parts.length - 1] : name;
    }

    private String resolveAppointmentDate(String consultationDate)
    {
        String value = defaultText(consultationDate, "");
        if (value.length() >= 10)
        {
            return value.substring(0, 10);
        }
        return value;
    }

    private List<Map<String, Object>> buildInvoiceAttachments(
            TcmConsultation consultation,
            Map<String, String> invoicePdf,
            JSONObject payload)
    {
        String resource = defaultText(invoicePdf.get("resource"),
                defaultText(invoicePdf.get("filePath"), payload.getString("invoicePdfPath")));
        List<Map<String, Object>> attachments = new ArrayList<>();
        if (StringUtils.isBlank(resource))
        {
            return attachments;
        }
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("resource", resource);
        attachment.put("fileName", "invoice-"
                + safeFileToken(resolveAttachmentDate(consultation, payload), "date")
                + "-" + resolveAttachmentVersion(consultation)
                + ".pdf");
        attachment.put("contentType", "application/pdf");
        attachments.add(attachment);
        return attachments;
    }

    private String resolveAttachmentDate(TcmConsultation consultation, JSONObject payload)
    {
        String date = consultation != null ? consultation.getConsultDate() : "";
        date = defaultText(date, payload != null ? payload.getString("consultationDate") : "");
        date = defaultText(date, payload != null ? payload.getString("date") : "");
        if (StringUtils.isNotBlank(date) && date.length() >= 10)
        {
            return date.substring(0, 10);
        }
        return LocalDate.now().toString();
    }

    private String resolveAttachmentVersion(TcmConsultation consultation)
    {
        int version = consultation != null && consultation.getVersion() != null && consultation.getVersion() > 0
                ? consultation.getVersion()
                : 1;
        return "v" + version;
    }

    private String safeFileToken(String value, String fallback)
    {
        String text = defaultText(value, fallback);
        text = text.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return StringUtils.isNotBlank(text) ? text : fallback;
    }

    private String buildInvoiceFallbackBody(Map<String, Object> variables)
    {
        return "您好 " + stringValue(variables.get("patientName")) + "，您的发票已生成。\n\n"
                + "问诊编号：" + stringValue(variables.get("consultationId")) + "\n"
                + "日期：" + stringValue(variables.get("consultationDate")) + "\n"
                + "金额：" + stringValue(variables.get("amount")) + "\n"
                + "发票 PDF 已随邮件附件发送。\n\n"
                + "感谢您的到访。";
    }

    private String buildReturnUrl(String query)
    {
        String base = publicAppBaseUrl;
        if (StringUtils.isBlank(base))
        {
            base = "http://127.0.0.1:5173";
        }
        if (!base.endsWith("/"))
        {
            base += "/";
        }
        return base + "cashier?" + query;
    }

    private BigDecimal getOutstandingAmount(JSONObject payload)
    {
        BigDecimal total = toBigDecimal(payload.get("totalAmount"));
        BigDecimal paid = BigDecimal.ZERO;
        JSONArray records = payload.getJSONArray("paymentRecords");
        if (records != null)
        {
            for (int i = 0; i < records.size(); i++)
            {
                JSONObject record = records.getJSONObject(i);
                if (record != null)
                {
                    paid = paid.add(toBigDecimal(record.get("amount")));
                }
            }
        }
        BigDecimal outstanding = total.subtract(paid);
        return outstanding.compareTo(BigDecimal.ZERO) > 0 ? outstanding : BigDecimal.ZERO;
    }

    private void verifySignature(String payload, String signatureHeader, String webhookSecret)
    {
        if (StringUtils.isBlank(signatureHeader))
        {
            throw new ServiceException("Missing Stripe-Signature");
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(","))
        {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) continue;
            String key = pair[0].trim();
            String value = pair[1].trim();
            if ("t".equals(key)) timestamp = value;
            if ("v1".equals(key)) signatures.add(value);
        }
        if (StringUtils.isBlank(timestamp) || signatures.isEmpty())
        {
            throw new ServiceException("Invalid Stripe-Signature");
        }
        long signedAt;
        try
        {
            signedAt = Long.parseLong(timestamp);
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("Invalid Stripe-Signature timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (signedAt < now - 300 || signedAt > now + 300)
        {
            throw new ServiceException("Expired Stripe webhook signature");
        }
        String expected = hmacSha256(timestamp + "." + payload, webhookSecret);
        boolean matched = false;
        for (String signature : signatures)
        {
            if (constantTimeEquals(expected, signature))
            {
                matched = true;
                break;
            }
        }
        if (!matched)
        {
            throw new ServiceException("Invalid Stripe webhook signature");
        }
    }

    private String hmacSha256(String value, String secret)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes)
            {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new ServiceException("Stripe signature verification failed");
        }
    }

    private boolean constantTimeEquals(String a, String b)
    {
        if (a == null || b == null || a.length() != b.length())
        {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++)
        {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private JSONObject parsePayload(String value)
    {
        if (StringUtils.isNotBlank(value))
        {
            try
            {
                return JSON.parseObject(value);
            }
            catch (Exception ignored) {}
        }
        return new JSONObject();
    }

    private BigDecimal toBigDecimal(Object value)
    {
        if (value == null) return BigDecimal.ZERO;
        try
        {
            return new BigDecimal(String.valueOf(value));
        }
        catch (Exception e)
        {
            return BigDecimal.ZERO;
        }
    }

    private String defaultText(String value, String fallback)
    {
        return StringUtils.isNotBlank(value) ? value.trim() : fallback;
    }

    private String normalizeClinicName(String value)
    {
        String text = defaultText(value, "");
        if (StringUtils.isBlank(text)
                || "TCM Clinic".equalsIgnoreCase(text)
                || "TCM Clinic Management System".equalsIgnoreCase(text)
                || "\u8bca\u6240".equals(text))
        {
            return DEFAULT_CLINIC_NAME;
        }
        return text;
    }

    private String stringValue(Object value)
    {
        return value != null ? String.valueOf(value) : "";
    }

    private Map<String, Object> ok(boolean processed, String type)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", true);
        result.put("processed", processed);
        result.put("type", type);
        return result;
    }
}
