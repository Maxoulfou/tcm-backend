package com.ruoyi.hospital.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.service.ITcmAppointmentService;
import com.ruoyi.hospital.service.ITcmAppointmentNotificationService;
import com.ruoyi.hospital.service.ITcmAuditLogService;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.utils.PayloadUtils;
import com.ruoyi.hospital.utils.PrivacyUtils;

@RestController
@RequestMapping("/api/appointments")
public class TcmAppointmentController {
    @Autowired
    private ITcmAppointmentService appointmentService;

    @Autowired
    private ITcmPatientService patientService;

    @Autowired
    private ITcmConsultationService consultationService;

    @Autowired
    private ITcmAuditLogService auditLogService;

    @Autowired
    private ITcmAppointmentNotificationService appointmentNotificationService;

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,apprentice')")
    @GetMapping("")
    public List<Map<String, Object>> list() {
        List<TcmAppointment> appointments = appointmentService.selectTcmAppointmentList(new TcmAppointment());
        if (PrivacyUtils.isAdmin()) {
            return PayloadUtils.flattenAppointments(appointments);
        }
        List<TcmPatient> patients = patientService.selectTcmPatientList(new TcmPatient());
        List<TcmConsultation> consultations = consultationService.selectTcmConsultationList(new TcmConsultation());
        Set<String> accessiblePatientIds = PrivacyUtils.collectAccessiblePatientIds(
                patients,
                consultations,
                appointments);
        return PayloadUtils.flattenAppointments(
                PrivacyUtils.filterAppointments(
                        appointments,
                        accessiblePatientIds));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,apprentice')")
    @GetMapping("/availability")
    public Map<String, Object> availability(
            @RequestParam String date,
            @RequestParam String serviceType,
            @RequestParam(required = false) String practitionerId,
            @RequestParam(required = false) String roomId,
            @RequestParam(required = false) String excludeId) {
        return appointmentService.getAvailability(date, serviceType, practitionerId, roomId, excludeId);
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PostMapping("")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        body.putIfAbsent("bookingSource", "internal");
        TcmAppointment appointment = PayloadUtils.toAppointment(body);
        if (appointment.getPatientId() != null && !appointment.getPatientId().trim().isEmpty()) {
            ensurePatientAccessible(appointment.getPatientId());
        }
        appointmentService.insertTcmAppointment(appointment);
        TcmAppointment created = appointmentService.selectTcmAppointmentById(appointment.getId());
        assignPrimaryPractitionerIfMissing(created);
        appointmentNotificationService.handleAppointmentCreated(created);
        logAppointmentAction(
                created,
                "CREATE",
                "create appointment: " + safeValue(created.getServiceType()) + " " + safeValue(created.getStartTime()));
        return PayloadUtils.flatten(created);
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        TcmAppointment existing = requireAppointment(id);
        ensureAppointmentAccessible(existing);
        TcmAppointment appointment = PayloadUtils.toAppointment(body);
        appointment.setId(id);
        // Ordinary edits cannot replace server-owned intake tokens or submission state.
        appointment.setIntakeToken(null);
        appointment.setIntakeSubmitted(null);
        if (appointment.getPatientId() != null && !appointment.getPatientId().trim().isEmpty()) {
            ensurePatientAccessible(appointment.getPatientId());
        }
        appointmentService.updateTcmAppointment(appointment);
        TcmAppointment updated = appointmentService.selectTcmAppointmentById(id);
        assignPrimaryPractitionerIfMissing(updated);
        appointmentNotificationService.handleAppointmentUpdated(existing, updated);
        logAppointmentAction(
                updated,
                "UPDATE",
                "update appointment: " + safeValue(updated.getServiceType()) + " " + safeValue(updated.getStartTime()));
        return PayloadUtils.flatten(updated);
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PatchMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        TcmAppointment existing = requireAppointment(id);
        ensureAppointmentAccessible(existing);
        TcmAppointment updated = appointmentService.updateStatus(id, body.get("status"));
        appointmentNotificationService.handleAppointmentStatusChanged(existing, updated);
        logAppointmentAction(updated, "STATUS_CHANGE",
                "appointment status changed to: " + safeValue(updated.getStatus()));
        return PayloadUtils.flatten(updated);
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PostMapping("/check-slot")
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkSlot(@RequestBody Map<String, String> body) {
        Map<String, Object> result = appointmentService.checkSlot(
                body.get("practitionerId"),
                body.get("roomId"),
                body.get("serviceType"),
                body.get("startTime"),
                body.get("endTime"),
                body.get("excludeId"));
        if (Boolean.FALSE.equals(result.get("available"))) {
            List<String> conflicts = (List<String>) result.get("conflicts");
            if (conflicts != null && !conflicts.isEmpty()) {
                result.put("reason", String.join(", ", conflicts));
            } else {
                result.put("reason", "slot is unavailable");
            }
        }
        return result;
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PostMapping("/{id}/intake-link")
    public Map<String, Object> generateIntakeLink(@PathVariable String id) {
        TcmAppointment appointment = requireAppointment(id);
        ensureAppointmentAccessible(appointment);
        String token = UUID.randomUUID().toString().replace("-", "");
        appointment.setIntakeToken(token);
        appointment.setIntakeSubmitted(0);
        appointmentService.updateTcmAppointment(appointment);
        logAppointmentAction(appointment, "GENERATE_INTAKE_LINK", "generate intake form link");
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        return result;
    }

    private TcmAppointment requireAppointment(String id) {
        TcmAppointment appointment = appointmentService.selectTcmAppointmentById(id);
        if (appointment == null) {
            throw new ServiceException("appointment not found");
        }
        return appointment;
    }

    private void ensurePatientAccessible(String patientId) {
        TcmPatient patient = patientService.selectTcmPatientById(patientId);
        if (patient == null) {
            throw new ServiceException("patient not found");
        }
        List<TcmConsultation> consultations = consultationService.selectTcmConsultationList(new TcmConsultation());
        if (!PrivacyUtils.canAccessPatient(
                patient,
                consultations,
                appointmentService.selectTcmAppointmentList(new TcmAppointment()))) {
            throw new ServiceException("access denied");
        }
    }

    private void ensureAppointmentAccessible(TcmAppointment appointment) {
        if (appointment.getPatientId() == null || appointment.getPatientId().trim().isEmpty()) {
            return;
        }
        List<TcmPatient> patients = patientService.selectTcmPatientList(new TcmPatient());
        List<TcmConsultation> consultations = consultationService.selectTcmConsultationList(new TcmConsultation());
        Set<String> accessiblePatientIds = PrivacyUtils.collectAccessiblePatientIds(
                patients,
                consultations,
                appointmentService.selectTcmAppointmentList(new TcmAppointment()));
        if (!accessiblePatientIds.contains(appointment.getPatientId())) {
            throw new ServiceException("access denied");
        }
    }

    private void assignPrimaryPractitionerIfMissing(TcmAppointment appointment) {
        if (appointment == null
                || appointment.getPatientId() == null
                || appointment.getPatientId().trim().isEmpty()
                || appointment.getPractitionerId() == null
                || appointment.getPractitionerId().trim().isEmpty()) {
            return;
        }
        TcmPatient patient = patientService.selectTcmPatientById(appointment.getPatientId());
        if (patient == null
                || (patient.getPractitionerId() != null && !patient.getPractitionerId().trim().isEmpty())) {
            return;
        }
        patient.setPractitionerId(appointment.getPractitionerId());
        patientService.updateTcmPatient(patient);
    }

    private void logAppointmentAction(TcmAppointment appointment, String action, String details) {
        if (appointment == null) {
            return;
        }
        auditLogService.log(
                "appointment",
                appointment.getId(),
                resolveAppointmentTargetName(appointment),
                action,
                String.valueOf(SecurityUtils.getUserId()),
                details);
    }

    private String resolveAppointmentTargetName(TcmAppointment appointment) {
        String patientName = appointment.getPatientId();
        if (appointment.getPatientId() != null && !appointment.getPatientId().isEmpty()) {
            TcmPatient patient = patientService.selectTcmPatientById(appointment.getPatientId());
            if (patient != null && patient.getName() != null && !patient.getName().isEmpty()) {
                patientName = patient.getName();
            }
        }
        if (appointment.getStartTime() != null && !appointment.getStartTime().isEmpty()) {
            return safeValue(patientName) + " @ " + appointment.getStartTime();
        }
        return safeValue(patientName);
    }

    private String safeValue(String value) {
        return value != null && !value.isEmpty() ? value : "-";
    }
}
