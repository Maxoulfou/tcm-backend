package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.controller.TcmExceptionHandler;
import com.ruoyi.hospital.controller.TcmStripePaymentController;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmEmailService;
import com.ruoyi.hospital.service.ITcmPdfService;
import com.ruoyi.hospital.service.ITcmSettingsService;
import com.ruoyi.hospital.service.ProviderPaymentResult;

class TcmStripeWebhookAcceptanceTest
{
    private TcmStripePaymentServiceImpl service;
    private ITcmConsultationService consultations;
    private ITcmSettingsService settings;
    private TcmPatientMapper patients;
    private ITcmPdfService pdf;
    private ITcmEmailService email;
    private List<Runnable> tasks;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        service = new TcmStripePaymentServiceImpl();
        consultations = mock(ITcmConsultationService.class);
        settings = mock(ITcmSettingsService.class);
        patients = mock(TcmPatientMapper.class);
        pdf = mock(ITcmPdfService.class);
        email = mock(ITcmEmailService.class);
        tasks = new ArrayList<>();
        ReflectionTestUtils.setField(service, "consultationService", consultations);
        ReflectionTestUtils.setField(service, "settingsService", settings);
        ReflectionTestUtils.setField(service, "patientMapper", patients);
        ReflectionTestUtils.setField(service, "pdfService", pdf);
        ReflectionTestUtils.setField(service, "emailService", email);
        ReflectionTestUtils.setField(service, "stripeWebhookTaskExecutor", (TaskExecutor) tasks::add);
        when(settings.getStripeWebhookSecret()).thenReturn("whsec_acceptance");
        when(consultations.recordProviderPayment(anyString(), anyString(), anyMap()))
                .thenReturn(new ProviderPaymentResult(consultation(), true));
        TcmStripePaymentController controller = new TcmStripePaymentController();
        ReflectionTestUtils.setField(controller, "stripePaymentService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new TcmExceptionHandler()).build();
    }

    @Test
    void signedUnsupportedEventAndBothWebhookRoutesReturn200WithoutRedirect() throws Exception
    {
        String payload = event("acceptance.probe", "paid");
        for (String path : new String[] {"/api/stripe/webhook", "/api/stripe/webhook/"})
        {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(payload)
                    .header("Stripe-Signature", signature(payload, Instant.now().getEpochSecond())))
                    .andExpect(status().isOk()).andExpect(header().doesNotExist("Location"))
                    .andExpect(jsonPath("$.received").value(true)).andExpect(jsonPath("$.processed").value(false));
        }
        verifyNoInteractions(consultations, patients, pdf, email);
    }

    @Test
    void absentSigningSecretRejectsInsteadOfAcceptingForgedPayment()
    {
        when(settings.getStripeWebhookSecret()).thenReturn("");
        assertThrows(ServiceException.class, () -> service.handleWebhook(event("checkout.session.completed", "paid"), ""));
        verifyNoInteractions(consultations);
    }

    @Test
    void missingAndMalformedSignaturesReturn400() throws Exception
    {
        String payload = event("checkout.session.completed", "paid");
        for (String header : new String[] {"", "t=not-a-number,v1=invalid", "t=999999999999999999999999,v1=invalid"})
        {
            mvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content(payload)
                    .header("Stripe-Signature", header)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(consultations);
    }

    @Test
    void expiredFutureAndOverflowTimestampsCannotBypassReplayTolerance()
    {
        String payload = event("checkout.session.completed", "paid");
        long now = Instant.now().getEpochSecond();
        for (long timestamp : new long[] {now - 301, now + 600, Long.MIN_VALUE, Long.MAX_VALUE})
        {
            assertThrows(ServiceException.class, () -> service.handleWebhook(payload, signature(payload, timestamp)));
        }
        verifyNoInteractions(consultations);
    }

    @Test
    void changingRawBodyInvalidatesSignature()
    {
        String payload = event("checkout.session.completed", "paid");
        assertThrows(ServiceException.class, () -> service.handleWebhook(payload + " ", signature(payload, Instant.now().getEpochSecond())));
        verifyNoInteractions(consultations);
    }

    @Test
    void completedButUnpaidCheckoutDoesNotRecordPayment()
    {
        for (String paymentStatus : new String[] {"unpaid", "no_payment_required", ""})
        {
            String payload = event("checkout.session.completed", paymentStatus);
            assertFalse((Boolean) service.handleWebhook(payload, signature(payload, Instant.now().getEpochSecond())).get("processed"));
        }
        verifyNoInteractions(consultations, pdf, email);
    }

    @Test
    void delayedCheckoutRecordsOnlyWhenAsyncSettlementSucceeds()
    {
        String payload = event("checkout.session.async_payment_succeeded", "paid");
        service.handleWebhook(payload, signature(payload, Instant.now().getEpochSecond()));
        verify(consultations).recordProviderPayment(eq("consult-acceptance"), eq("stripe"), anyMap());
    }

    @Test
    void terminalWebhookAcknowledgesBeforePdfOrMailAndGeneratesPdfWithoutRecipient()
    {
        TcmConsultation recorded = consultation();
        when(consultations.recordProviderPayment(anyString(), anyString(), anyMap())).thenReturn(new ProviderPaymentResult(recorded, true));
        String payload = event("payment_intent.succeeded", "paid");
        assertTrue((Boolean) service.handleWebhook(payload, signature(payload, Instant.now().getEpochSecond())).get("processed"));
        verifyNoInteractions(patients, pdf, email);
        assertEquals(1, tasks.size());
        tasks.get(0).run();
        verify(pdf).generateInvoice("consult-acceptance");
        verifyNoInteractions(email);
    }

    @Test
    void duplicateCheckoutDoesNotScheduleAnotherInvoice()
    {
        TcmConsultation existing = consultation();
        existing.setPayload("{\"paymentRecords\":[{\"stripeSessionId\":\"cs_acceptance\"}]}");
        when(consultations.recordProviderPayment(anyString(), anyString(), anyMap()))
                .thenReturn(new ProviderPaymentResult(existing, false));
        String payload = event("checkout.session.completed", "paid");
        service.handleWebhook(payload, signature(payload, Instant.now().getEpochSecond()));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void temporaryDatabaseFailureReturns500ForStripeRetry() throws Exception
    {
        when(consultations.recordProviderPayment(anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("acceptance database unavailable"));
        String payload = event("checkout.session.completed", "paid");
        mvc.perform(post("/api/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", signature(payload, Instant.now().getEpochSecond())))
                .andExpect(status().isInternalServerError());
        assertTrue(tasks.isEmpty());
    }

    @Test
    void rejectedInvoiceTaskDoesNotTurnCommittedPaymentIntoWebhookFailure()
    {
        ReflectionTestUtils.setField(service, "stripeWebhookTaskExecutor", (TaskExecutor) task -> {
            throw new RejectedExecutionException("acceptance queue full");
        });
        when(consultations.recordProviderPayment(anyString(), anyString(), anyMap()))
                .thenReturn(new ProviderPaymentResult(consultation(), true));
        String payload = event("checkout.session.completed", "paid");
        assertTrue((Boolean) service.handleWebhook(payload, signature(payload, Instant.now().getEpochSecond())).get("processed"));
        verify(consultations).recordProviderPayment(anyString(), anyString(), anyMap());
    }

    private TcmConsultation consultation()
    {
        TcmConsultation result = new TcmConsultation();
        result.setId("consult-acceptance");
        result.setPatientId("patient-acceptance");
        result.setPayload("{}");
        return result;
    }

    private String event(String type, String paymentStatus)
    {
        JSONObject object = JSON.parseObject("{\"id\":\"cs_acceptance\",\"payment_intent\":\"pi_acceptance\","
                + "\"amount_total\":1234,\"amount\":1234,\"amount_received\":1234,\"currency\":\"cad\","
                + "\"status\":\"succeeded\",\"metadata\":{\"consultationId\":\"consult-acceptance\"}}");
        object.put("payment_status", paymentStatus);
        JSONObject event = new JSONObject();
        event.put("id", "evt_acceptance");
        event.put("type", type);
        event.put("data", Map.of("object", object));
        return event.toJSONString();
    }

    private String signature(String payload, long timestamp)
    {
        String digest = ReflectionTestUtils.invokeMethod(service, "hmacSha256", timestamp + "." + payload, "whsec_acceptance");
        return "t=" + timestamp + ",v1=" + digest;
    }
}
