package com.ruoyi.hospital.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmAppointmentService;
import com.ruoyi.hospital.service.ITcmPatientService;

@ExtendWith(MockitoExtension.class)
class TcmIntakeControllerTest
{
    @Mock
    private ITcmAppointmentService appointmentService;

    @Mock
    private ITcmPatientService patientService;

    @Mock
    private ITcmAppointmentNotificationService appointmentNotificationService;

    private TcmIntakeController controller;

    @BeforeEach
    void setUp()
    {
        controller = new TcmIntakeController();
        ReflectionTestUtils.setField(controller, "appointmentService", appointmentService);
        ReflectionTestUtils.setField(controller, "patientService", patientService);
        ReflectionTestUtils.setField(controller, "appointmentNotificationService", appointmentNotificationService);
    }

    @Test
    void cancelAppointment_shouldDelegateToNotificationService()
    {
        TcmAppointment appointment = new TcmAppointment();
        appointment.setId("appt-9");
        appointment.setStatus("cancelled");
        when(appointmentNotificationService.cancelByIntakeToken("intake-token", "patient_intake_form"))
                .thenReturn(appointment);

        Map<String, Object> result = controller.cancelAppointment("intake-token");

        assertEquals(Boolean.TRUE, result.get("ok"));
        assertEquals("appt-9", result.get("appointmentId"));
        assertEquals("cancelled", result.get("status"));
        verify(appointmentNotificationService).cancelByIntakeToken("intake-token", "patient_intake_form");
    }

    @Test
    void appointmentIntakeUsesExplicitTokenConsumptionInsteadOfGenericAppointmentEdit()
    {
        TcmAppointment appointment = new TcmAppointment();
        appointment.setId("appointment"); appointment.setPatientId("patient"); appointment.setIntakeSubmitted(0);
        Map<String, Object> form = Map.of("chiefComplaint", "Pain");
        when(appointmentService.selectTcmAppointmentByIntakeToken("token")).thenReturn(appointment);
        when(appointmentService.completeIntake("appointment", "token", form)).thenReturn(appointment);

        assertEquals(true, controller.submitIntakeForm("token", form).get("ok"));
        verify(appointmentService).completeIntake("appointment", "token", form);
        verify(patientService).saveLatestIntakeForm("patient", form);
        verify(appointmentService, never()).updateTcmAppointment(any());
    }

    @Test
    void submitIntakeForm_shouldRejectMissingChiefComplaintBeforeSaving()
    {
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("chiefComplaint", "  ");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.submitIntakeForm("intake-token", formData));

        assertEquals("Please fill in the chief complaint first", ex.getMessage());
        verify(appointmentService, never()).selectTcmAppointmentByIntakeToken(anyString());
        verify(patientService, never()).saveIntakeFormByToken(anyString(), any());
    }
}
