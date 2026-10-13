package com.ruoyi.hospital.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.filter.RepeatableFilter;
import com.ruoyi.common.filter.XssFilter;
import com.ruoyi.hospital.service.ITcmSettingsService;

class TcmStripeRawBodyFilterTest
{
    private static final String PAYLOAD = "{\n  \"id\":\"evt_raw_body\",\n  \"type\":\"acceptance.probe\",\n"
            + "  \"data\":{\"object\":{\"description\":\"中文 <b>test</b>\"}}\n} \r\n";

    @Test void bothWebhookRoutesPreserveSignedWhitespaceHtmlAndUtf8Bytes() throws Exception
    {
        TcmStripePaymentServiceImpl service = service();
        for (String path : new String[] {"/api/stripe/webhook", "/api/stripe/webhook/"})
        {
            String received = filter(path, PAYLOAD);
            assertEquals(PAYLOAD, received);
            Map<String, Object> result = service.handleWebhook(received, sign(service, PAYLOAD));
            assertTrue((Boolean) result.get("received"));
        }
    }

    @Test void filterChainDoesNotHideWhitespaceOrSubstantiveBodyTampering() throws Exception
    {
        TcmStripePaymentServiceImpl service = service();
        String signed = sign(service, PAYLOAD);
        String changedWhitespace = filter("/api/stripe/webhook", PAYLOAD + " ");
        String changedValue = filter("/api/stripe/webhook", PAYLOAD.replace("test", "changed"));
        assertThrows(ServiceException.class, () -> service.handleWebhook(changedWhitespace, signed));
        assertThrows(ServiceException.class, () -> service.handleWebhook(changedValue, signed));
    }

    @Test void otherNestedApiRoutesRetainExistingXssFiltering() throws Exception
    {
        String input = "{\"value\":\"<script>alert(1)</script>\"}";
        for (String path : new String[] {"/api/patients/synthetic", "/api/stripe/webhook-extra"})
        {
            assertNotEquals(input, filter(path, input));
        }
    }

    private String filter(String path, String body) throws Exception
    {
        XssFilter xss = new XssFilter();
        MockFilterConfig config = new MockFilterConfig();
        config.addInitParameter("excludes", "/system/notice,/api/*");
        xss.init(config);
        RepeatableFilter repeatable = new RepeatableFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setContentType("application/json;charset=UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> result = new AtomicReference<>();
        xss.doFilter(request, new MockHttpServletResponse(), (first, response) ->
                repeatable.doFilter(first, response, (wire, ignored) ->
                        result.set(new String(wire.getInputStream().readAllBytes(), StandardCharsets.UTF_8))));
        return result.get();
    }

    private TcmStripePaymentServiceImpl service()
    {
        TcmStripePaymentServiceImpl result = new TcmStripePaymentServiceImpl();
        ITcmSettingsService settings = mock(ITcmSettingsService.class);
        when(settings.getStripeWebhookSecret()).thenReturn("whsec_raw_body");
        ReflectionTestUtils.setField(result, "settingsService", settings);
        return result;
    }

    private String sign(TcmStripePaymentServiceImpl service, String body)
    {
        long time = Instant.now().getEpochSecond();
        String digest = ReflectionTestUtils.invokeMethod(service, "hmacSha256", time + "." + body, "whsec_raw_body");
        return "t=" + time + ",v1=" + digest;
    }
}
