package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.mapper.TcmConsultationMapper;
import com.ruoyi.hospital.mapper.TcmConsultationModMapper;
import com.ruoyi.hospital.mapper.TcmPatientMapper;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmPatientFileService;
import com.ruoyi.hospital.service.ITcmPdfService;

class TcmStripePaymentPersistenceTest
{
    private JdbcTemplate jdbc;
    private ITcmConsultationService service;
    private ITcmPdfService pdf;
    private TcmConsultationMapper mapper;

    @BeforeEach void setup()
    {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE consultation (id VARCHAR(64) PRIMARY KEY, payload CLOB)");
        jdbc.update("INSERT INTO consultation VALUES (?, ?)", "payment", "{\"totalAmount\":125,\"paymentRecords\":[]}");
        TcmConsultationServiceImpl target = new TcmConsultationServiceImpl();
        mapper = mock(TcmConsultationMapper.class);
        pdf = mock(ITcmPdfService.class);
        ReflectionTestUtils.setField(target, "consultationMapper", mapper);
        ReflectionTestUtils.setField(target, "modMapper", mock(TcmConsultationModMapper.class));
        ReflectionTestUtils.setField(target, "pdfService", pdf);
        ReflectionTestUtils.setField(target, "patientMapper", mock(TcmPatientMapper.class));
        ReflectionTestUtils.setField(target, "patientFileService", mock(ITcmPatientFileService.class));
        when(mapper.selectTcmConsultationForUpdate("payment")).thenAnswer(call -> load(true));
        when(mapper.selectTcmConsultationById("payment")).thenAnswer(call -> load(false));
        doAnswer(call -> {
            TcmConsultation record = call.getArgument(0);
            return jdbc.update("UPDATE consultation SET payload=? WHERE id=?", record.getPayload(), record.getId());
        }).when(mapper).updateTcmConsultation(any());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        service = (ITcmConsultationService) factory.getProxy();
    }

    @Test void duplicateDeliveriesIncludingConcurrentRequestsPersistOnePayment() throws Exception
    {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        AtomicInteger invoiceDispatchWinners = new AtomicInteger();
        try
        {
            Runnable deliver = () -> {
                await(start);
                if (service.recordProviderPayment("payment", "stripe", payment()).isNewlyRecorded())
                {
                    invoiceDispatchWinners.incrementAndGet();
                }
            };
            Future<?> first = workers.submit(deliver);
            Future<?> second = workers.submit(deliver);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        finally { workers.shutdownNow(); }
        assertFalse(service.recordProviderPayment("payment", "stripe", payment()).isNewlyRecorded());
        assertEquals(1, invoiceDispatchWinners.get());
        assertEquals(1, JSON.parseObject(load(false).getPayload()).getJSONArray("paymentRecords").size());
        verifyNoInteractions(pdf);
    }

    @Test void databaseFailureRollsBackAndRetryPersistsPaymentOnce()
    {
        doAnswer(call -> {
            TcmConsultation record = call.getArgument(0);
            jdbc.update("UPDATE consultation SET payload=? WHERE id=?", record.getPayload(), record.getId());
            throw new IllegalStateException("acceptance failure after SQL");
        }).when(mapper).updateTcmConsultation(any());
        assertThrows(IllegalStateException.class, () -> service.recordProviderPayment("payment", "stripe", payment()));
        assertEquals(0, JSON.parseObject(load(false).getPayload()).getJSONArray("paymentRecords").size());
        doAnswer(call -> {
            TcmConsultation record = call.getArgument(0);
            return jdbc.update("UPDATE consultation SET payload=? WHERE id=?", record.getPayload(), record.getId());
        }).when(mapper).updateTcmConsultation(any());
        service.recordProviderPayment("payment", "stripe", payment());
        assertEquals(1, JSON.parseObject(load(false).getPayload()).getJSONArray("paymentRecords").size());
        verifyNoInteractions(pdf);
    }

    private TcmConsultation load(boolean lock)
    {
        String payload = jdbc.queryForObject("SELECT payload FROM consultation WHERE id='payment'" + (lock ? " FOR UPDATE" : ""), String.class);
        TcmConsultation result = new TcmConsultation();
        result.setId("payment"); result.setStatus("completed"); result.setPayload(payload); return result;
    }

    private Map<String, Object> payment()
    {
        return new HashMap<>(Map.of("provider", "stripe", "stripeSessionId", "cs_acceptance", "stripePaymentIntentId", "pi_acceptance", "amount", new BigDecimal("125")));
    }

    private void await(CountDownLatch latch)
    {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
