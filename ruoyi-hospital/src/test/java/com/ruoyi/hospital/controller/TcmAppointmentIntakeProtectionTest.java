package com.ruoyi.hospital.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.service.ITcmAppointmentService;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmAuditLogService;

class TcmAppointmentIntakeProtectionTest {
    private final ITcmAppointmentService service = mock(ITcmAppointmentService.class);
    private final TcmAppointmentController controller = new TcmAppointmentController();
    private TcmAppointment existing;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(controller, "appointmentService", service);
        ReflectionTestUtils.setField(controller, "appointmentNotificationService", mock(ITcmAppointmentNotificationService.class));
        ReflectionTestUtils.setField(controller, "auditLogService", mock(ITcmAuditLogService.class));
        SysUser user = new SysUser(); user.setUserId(1L);
        LoginUser login = new LoginUser(user, Collections.emptySet()); login.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
        existing = new TcmAppointment(); existing.setId("appointment");
        existing.setIntakeToken("server-token"); existing.setIntakeSubmitted(1);
        when(service.selectTcmAppointmentById(existing.getId())).thenReturn(existing);
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void ordinaryPutCannotOverwriteIntakeTokenOrSubmittedState() {
        controller.update(existing.getId(), Map.of("intakeToken", "forged-token", "intakeSubmitted", 0));
        ArgumentCaptor<TcmAppointment> saved = ArgumentCaptor.forClass(TcmAppointment.class);
        verify(service).updateTcmAppointment(saved.capture());
        assertNull(saved.getValue().getIntakeToken());
        assertNull(saved.getValue().getIntakeSubmitted());
    }

    @Test void explicitIntakeLinkGenerationStillWritesANewTokenAndResetsSubmittedState() {
        Map<String, Object> result = controller.generateIntakeLink(existing.getId());
        ArgumentCaptor<TcmAppointment> saved = ArgumentCaptor.forClass(TcmAppointment.class);
        verify(service).updateTcmAppointment(saved.capture());
        assertEquals(result.get("token"), saved.getValue().getIntakeToken());
        assertNotEquals("server-token", saved.getValue().getIntakeToken());
        assertEquals(0, saved.getValue().getIntakeSubmitted());
    }
}
