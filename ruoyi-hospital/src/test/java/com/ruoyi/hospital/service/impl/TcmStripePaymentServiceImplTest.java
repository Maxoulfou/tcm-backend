package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmSettingsService;
import com.ruoyi.hospital.service.ProviderPaymentResult;

@ExtendWith(MockitoExtension.class)
class TcmStripePaymentServiceImplTest
{
    @Mock
    private ITcmConsultationService consultationService;

    @Mock
    private TcmPatientMapper patientMapper;

    @Mock
    private ITcmEmailService emailService;

    @Mock
    private ITcmSettingsService settingsService;

    private TcmStripePaymentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        service = new TcmStripePaymentServiceImpl();
        ReflectionTestUtils.setField(service, "consultationService", consultationService);
        ReflectionTestUtils.setField(service, "patientMapper", patientMapper);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "settingsService", settingsService);
        when(settingsService.getStripeWebhookSecret()).thenReturn("whsec_test");
    }

    @Test
    void handleWebhook_shouldRecordTerminalPaymentIntentSucceeded()
    {
        TcmConsultation recorded = new TcmConsultation();
        recorded.setId("consult-1");
        recorded.setPayload("{}");


        when(consultationService.recordProviderPayment(eq("consult-1"), eq("stripe_terminal"), anyMap()))
                .thenReturn(new ProviderPaymentResult(recorded, true));
        when(settingsService.getStripeTerminalReaderId()).thenReturn("tmr_reader");

        String payload = "{"
                + "\"id\":\"evt_pi\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"livemode\":true,"
                + "\"data\":{\"object\":{"
                + "\"id\":\"pi_123\","
                + "\"status\":\"succeeded\","
                + "\"amount\":1234,"
                + "\"amount_received\":1234,"
                + "\"currency\":\"cad\","
                + "\"livemode\":true,"
                + "\"metadata\":{\"consultationId\":\"consult-1\"}"
                + "}}"
                + "}";

        Map<String, Object> result = service.handleWebhook(payload, sign(payload));

        assertTrue((Boolean) result.get("received"));
        assertTrue((Boolean) result.get("processed"));
        assertEquals("payment_intent.succeeded", result.get("type"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paymentInfoCaptor = ArgumentCaptor.forClass(Map.class);
        verify(consultationService).recordProviderPayment(
                eq("consult-1"),
                eq("stripe_terminal"),
                paymentInfoCaptor.capture());
        Map<String, Object> paymentInfo = paymentInfoCaptor.getValue();
        assertEquals("stripe_terminal", paymentInfo.get("provider"));
        assertEquals("bankcard", paymentInfo.get("paymentMethod"));
        assertEquals(new BigDecimal("12.34"), paymentInfo.get("amount"));
        assertEquals("CAD", paymentInfo.get("currency"));
        assertEquals("pi_123", paymentInfo.get("stripePaymentIntentId"));
        assertEquals("tmr_reader", paymentInfo.get("stripeReaderId"));
    }

    @Test
    void handleWebhook_shouldAcknowledgeReaderEventsWithoutRecordingPayment()
    {
        String payload = "{"
                + "\"id\":\"evt_reader\","
                + "\"type\":\"terminal.reader.action_failed\","
                + "\"data\":{\"object\":{\"id\":\"tmr_reader\"}}"
                + "}";

        Map<String, Object> result = service.handleWebhook(payload, sign(payload));

        assertTrue((Boolean) result.get("received"));
        assertFalse((Boolean) result.get("processed"));
        assertEquals("terminal.reader.action_failed", result.get("type"));
        verify(consultationService, never()).recordProviderPayment(eq("consult-1"), eq("stripe_terminal"), anyMap());
    }

    @Test
    void handleWebhook_shouldAcceptAnyMatchingV1Signature()
    {
        String payload = "{"
                + "\"id\":\"evt_reader\","
                + "\"type\":\"terminal.reader.action_failed\","
                + "\"data\":{\"object\":{\"id\":\"tmr_reader\"}}"
                + "}";
        String webhookSecret = "whsec_test";
        long timestamp = System.currentTimeMillis() / 1000L;
        String signature = ReflectionTestUtils.invokeMethod(
                service,
                "hmacSha256",
                timestamp + "." + payload,
                webhookSecret);
        when(settingsService.getStripeWebhookSecret()).thenReturn(webhookSecret);

        Map<String, Object> result = service.handleWebhook(
                payload,
                "t=" + timestamp + ",v1=" + signature + ",v1=invalid_signature");

        assertTrue((Boolean) result.get("received"));
        assertFalse((Boolean) result.get("processed"));
        assertEquals("terminal.reader.action_failed", result.get("type"));
    }

    @Test
    void handleWebhook_shouldFetchStripeEventWhenSignatureMismatches()
    {
        StripeEventFallbackService fallbackService = new StripeEventFallbackService();
        ReflectionTestUtils.setField(fallbackService, "consultationService", consultationService);
        ReflectionTestUtils.setField(fallbackService, "patientMapper", patientMapper);
        ReflectionTestUtils.setField(fallbackService, "emailService", emailService);
        ReflectionTestUtils.setField(fallbackService, "settingsService", settingsService);

        TcmConsultation recorded = new TcmConsultation();
        recorded.setId("consult-1");
        recorded.setPayload("{}");

        when(settingsService.getStripeWebhookSecret()).thenReturn("whsec_test");
        when(settingsService.getStripeSecretKey()).thenReturn("sk_live_test");
        when(settingsService.getStripeTerminalReaderId()).thenReturn("tmr_reader");

        when(consultationService.recordProviderPayment(eq("consult-1"), eq("stripe_terminal"), anyMap()))
                .thenReturn(new ProviderPaymentResult(recorded, true));

        String payload = "{"
                + "\"id\":\"evt_api_verified\","
                + "\"type\":\"payment_intent.succeeded\","
                + "\"livemode\":true,"
                + "\"data\":{\"object\":{"
                + "\"id\":\"pi_123\","
                + "\"status\":\"succeeded\","
                + "\"amount\":12500,"
                + "\"amount_received\":12500,"
                + "\"currency\":\"cad\","
                + "\"livemode\":true,"
                + "\"metadata\":{\"consultationId\":\"consult-1\"}"
                + "}}"
                + "}";
        fallbackService.stripeEvent = com.alibaba.fastjson2.JSON.parseObject(payload);
        long timestamp = System.currentTimeMillis() / 1000L;

        Map<String, Object> result = fallbackService.handleWebhook(payload, "t=" + timestamp + ",v1=bad_signature");

        assertTrue((Boolean) result.get("received"));
        assertTrue((Boolean) result.get("processed"));
        assertEquals("payment_intent.succeeded", result.get("type"));
        assertTrue(fallbackService.requestedUrl.endsWith("/v1/events/evt_api_verified"));
        verify(consultationService).recordProviderPayment(eq("consult-1"), eq("stripe_terminal"), anyMap());
    }

    @Test
    void handleWebhook_shouldIsolateBothPaymentKindsBetweenClonedSites()
    {
        for (String type : new String[] { "checkout.session.completed", "payment_intent.succeeded" })
        {
            String legacy = "{\"id\":\"evt_legacy\",\"type\":\"" + type
                    + "\",\"data\":{\"object\":{\"status\":\"succeeded\","
                    + "\"metadata\":{\"consultationId\":\"consult-1\"}}}}";
            String clone = legacy.replace("\"consultationId\"", "\"tcmInstance\":\"yuanyuan\",\"consultationId\"");
            ReflectionTestUtils.setField(service, "instanceId", "yuanyuan");
            assertFalse((Boolean) service.handleWebhook(legacy, sign(legacy)).get("processed"));
            ReflectionTestUtils.setField(service, "instanceId", "");
            assertFalse((Boolean) service.handleWebhook(clone, sign(clone)).get("processed"));
        }
        org.mockito.Mockito.verifyNoInteractions(consultationService, patientMapper, emailService);
    }

    @Test
    void handleWebhook_shouldProcessCheckoutForItsOwnInstance()
    {
        ReflectionTestUtils.setField(service, "instanceId", "yuanyuan");
        when(consultationService.recordProviderPayment(eq("consult-1"), eq("stripe"), anyMap()))
                .thenReturn(new ProviderPaymentResult(null, false));
        String payload = "{\"id\":\"evt_clone\",\"type\":\"checkout.session.completed\","
                + "\"data\":{\"object\":{\"id\":\"cs_clone\",\"amount_total\":1234,\"payment_status\":\"paid\","
                + "\"metadata\":{\"consultationId\":\"consult-1\",\"tcmInstance\":\"yuanyuan\"}}}}";
        assertTrue((Boolean) service.handleWebhook(payload, sign(payload)).get("processed"));
        verify(consultationService).recordProviderPayment(eq("consult-1"), eq("stripe"), anyMap());
    }

    @Test
    void getTerminalPaymentStatus_shouldRejectForeignPaymentBeforeCapture()
    {
        StripeEventFallbackService isolated = new StripeEventFallbackService();
        ReflectionTestUtils.setField(isolated, "settingsService", settingsService);
        ReflectionTestUtils.setField(isolated, "instanceId", "yuanyuan");
        when(settingsService.getStripeSecretKey()).thenReturn("sk_test_dummy");
        when(settingsService.getStripeTerminalReaderId()).thenReturn("tmr_reader");
        org.mockito.Mockito.lenient().when(settingsService.getStripeWebhookSecret()).thenReturn("");
        isolated.stripeEvent = com.alibaba.fastjson2.JSON.parseObject(
                "{\"status\":\"requires_capture\",\"metadata\":{\"consultationId\":\"consult-1\"}}");
        org.junit.jupiter.api.Assertions.assertThrows(com.ruoyi.common.exception.ServiceException.class,
                () -> isolated.getTerminalPaymentStatus("consult-1", "pi_foreign"));
        org.mockito.Mockito.verifyNoInteractions(consultationService, emailService);
    }

    private String sign(String payload)
    {
        long timestamp = System.currentTimeMillis() / 1000L;
        String signature = ReflectionTestUtils.invokeMethod(service, "hmacSha256", timestamp + "." + payload, "whsec_test");
        return "t=" + timestamp + ",v1=" + signature;
    }

    private static class StripeEventFallbackService extends TcmStripePaymentServiceImpl
    {
        private com.alibaba.fastjson2.JSONObject stripeEvent;
        private String requestedUrl;

        @Override
        protected com.alibaba.fastjson2.JSONObject getStripeObject(String url)
        {
            requestedUrl = url;
            return stripeEvent;
        }
    }
}
