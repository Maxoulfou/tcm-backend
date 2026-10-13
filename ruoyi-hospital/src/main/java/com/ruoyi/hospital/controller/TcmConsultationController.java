package com.ruoyi.hospital.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.hospital.domain.TcmAppointment;
import com.ruoyi.hospital.domain.TcmConsultation;
import com.ruoyi.hospital.domain.TcmPatient;
import com.ruoyi.hospital.service.ITcmAppointmentService;
import com.ruoyi.hospital.service.ITcmConsultationService;
import com.ruoyi.hospital.service.ITcmPatientService;
import com.ruoyi.hospital.service.ITcmPdfService;
import com.ruoyi.hospital.utils.PayloadUtils;
import com.ruoyi.hospital.utils.PrivacyUtils;

@RestController
@RequestMapping("/api/consultations")
public class TcmConsultationController
{
    @Autowired
    private ITcmConsultationService consultationService;

    @Autowired
    private ITcmPatientService patientService;

    @Autowired
    private ITcmAppointmentService appointmentService;

    @Autowired
    private ITcmPdfService pdfService;

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,apprentice')")
    @GetMapping("")
    public List<Map<String, Object>> list()
    {
        List<TcmConsultation> allConsultations = consultationService.selectTcmConsultationList(new TcmConsultation());
        if (PrivacyUtils.isAdmin())
        {
            return PayloadUtils.flattenConsultations(allConsultations);
        }
        List<TcmPatient> patients = patientService.selectTcmPatientList(new TcmPatient());
        List<TcmAppointment> appointments = appointmentService.selectTcmAppointmentList(new TcmAppointment());
        return PayloadUtils.flattenConsultations(
                PrivacyUtils.filterConsultations(allConsultations, patients, appointments));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,apprentice')")
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(consultation);
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PostMapping("")
    public Map<String, Object> create(@RequestBody Map<String, Object> body)
    {
        TcmConsultation consultation = PayloadUtils.toConsultation(body);
        if (!PrivacyUtils.isAdmin() && PrivacyUtils.hasRole("practitioner"))
        {
            consultation.setPractitionerId(String.valueOf(SecurityUtils.getUserId()));
        }
        ensurePatientAccessibleForCreate(consultation);
        consultationService.insertTcmConsultation(consultation);
        return PayloadUtils.flatten(consultationService.selectTcmConsultationById(consultation.getId()));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body)
    {
        TcmConsultation existing = requireConsultation(id);
        ensureConsultationAccessible(existing);
        TcmConsultation consultation = PayloadUtils.toConsultation(body);
        consultation.setId(existing.getId());
        ensurePatientAccessible(consultation.getPatientId());
        consultationService.updateTcmConsultation(consultation, String.valueOf(SecurityUtils.getUserId()));
        return PayloadUtils.flatten(consultationService.selectTcmConsultationById(existing.getId()));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PatchMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.completeConsultation(consultation.getId(), String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PatchMapping("/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable String id)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.reactivateConsultation(consultation.getId(), String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,cashier')")
    @PatchMapping("/{id}/paid")
    public Map<String, Object> paid(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.markPaid(id, String.valueOf(SecurityUtils.getUserId()), body));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,cashier')")
    @PatchMapping("/{id}/invoice-pricing")
    public Map<String, Object> updateInvoicePricing(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.updateInvoicePricing(
                        id,
                        String.valueOf(SecurityUtils.getUserId()),
                        body != null ? body : new HashMap<>()));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PatchMapping("/{id}/prescriptions")
    public Map<String, Object> syncPrescription(
            @PathVariable String id,
            @RequestBody Map<String, Object> body)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.syncPrescription(id, body, String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner')")
    @PatchMapping("/{id}/prescriptions/{prescriptionId}/complete")
    public Map<String, Object> completePrescription(
            @PathVariable String id,
            @PathVariable String prescriptionId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.completePrescription(
                        id,
                        prescriptionId,
                        body != null ? body : new HashMap<>(),
                        String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,pharmacist')")
    @PatchMapping("/{id}/dispense")
    public Map<String, Object> dispense(
            @PathVariable String id,
            @RequestParam(value = "skipDeduct", required = false, defaultValue = "false") boolean skipDeduct)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.markDispensingComplete(id, String.valueOf(SecurityUtils.getUserId()), skipDeduct));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,pharmacist')")
    @PatchMapping("/{id}/prescriptions/{prescriptionId}/dispense")
    public Map<String, Object> dispensePrescription(
            @PathVariable String id,
            @PathVariable String prescriptionId)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.dispensePrescription(id, prescriptionId, String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PatchMapping("/{id}/prescriptions/{prescriptionId}/reopen")
    public Map<String, Object> reopenPrescription(
            @PathVariable String id,
            @PathVariable String prescriptionId)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.reopenPrescription(id, prescriptionId, String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PatchMapping("/{id}/prescriptions/{prescriptionId}/delete")
    public Map<String, Object> deletePrescription(
            @PathVariable String id,
            @PathVariable String prescriptionId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.deletePrescription(
                        id,
                        prescriptionId,
                        body != null ? body : new HashMap<>(),
                        String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @GetMapping("/deleted-prescriptions")
    public List<Map<String, Object>> deletedPrescriptions()
    {
        return consultationService.listDeletedPrescriptions();
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PatchMapping("/{id}/prescriptions/{prescriptionId}/restore-deleted")
    public Map<String, Object> restoreDeletedPrescription(
            @PathVariable String id,
            @PathVariable String prescriptionId)
    {
        ensureConsultationAccessible(requireConsultation(id));
        return PayloadUtils.flatten(
                consultationService.restoreDeletedPrescription(
                        id,
                        prescriptionId,
                        String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @DeleteMapping("/{id}/prescriptions/{prescriptionId}")
    public Map<String, Object> hardDeletePrescription(
            @PathVariable String id,
            @PathVariable String prescriptionId,
            @RequestParam(value = "restoreInventory", required = false, defaultValue = "true") boolean restoreInventory)
    {
        ensureConsultationAccessible(requireConsultation(id));
        return PayloadUtils.flatten(
                consultationService.permanentlyDeletePrescription(
                        id,
                        prescriptionId,
                        restoreInventory,
                        String.valueOf(SecurityUtils.getUserId())));
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,cashier,pharmacist')")
    @PostMapping("/{id}/payments")
    public Map<String, Object> createPayment(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body)
    {
        TcmConsultation consultation = requireConsultation(id);
        ensureConsultationAccessible(consultation);
        return PayloadUtils.flatten(
                consultationService.recordPayment(
                        id,
                        String.valueOf(SecurityUtils.getUserId()),
                        body != null ? body : new HashMap<>()));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PatchMapping("/{id}/delete")
    public Map<String, Object> softDelete(@PathVariable String id)
    {
        ensureConsultationAccessible(requireConsultation(id));
        return PayloadUtils.flatten(consultationService.softDeleteTcmConsultation(id));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @PatchMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id)
    {
        ensureConsultationAccessible(requireConsultation(id));
        return PayloadUtils.flatten(consultationService.restoreTcmConsultation(id));
    }

    @PreAuthorize("@ss.hasRole('admin')")
    @DeleteMapping("/{id}")
    public Map<String, Object> hardDelete(@PathVariable String id)
    {
        ensureConsultationAccessible(requireConsultation(id));
        consultationService.hardDeleteTcmConsultation(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,apprentice')")
    @PostMapping("/{id}/pdf/report")
    public Map<String, String> generateReport(@PathVariable String id)
    {
        ensureConsultationAccessible(requireConsultation(id));
        return pdfService.generateConsultationReport(id);
    }

    @PreAuthorize("@ss.hasAnyRoles('admin,practitioner,cashier')")
    @PostMapping("/{id}/pdf/invoice")
    public Map<String, String> generateInvoice(@PathVariable String id)
    {
        ensureConsultationAccessible(requireConsultation(id));
        return pdfService.generateInvoice(id);
    }

    private TcmConsultation requireConsultation(String id)
    {
        TcmConsultation consultation = consultationService.selectTcmConsultationById(id);
        if (consultation == null)
        {
            throw new ServiceException("consultation not found");
        }
        return consultation;
    }

    private void ensurePatientAccessible(String patientId)
    {
        TcmPatient patient = patientService.selectTcmPatientById(patientId);
        if (patient == null)
        {
            throw new ServiceException("patient not found");
        }
        List<TcmConsultation> consultations = consultationService.selectTcmConsultationList(new TcmConsultation());
        List<TcmAppointment> appointments = appointmentService.selectTcmAppointmentList(new TcmAppointment());
        if (!PrivacyUtils.canAccessPatient(patient, consultations, appointments))
        {
            throw new ServiceException("access denied");
        }
    }

    private void ensurePatientAccessibleForCreate(TcmConsultation consultation)
    {
        TcmPatient patient = patientService.selectTcmPatientById(consultation.getPatientId());
        if (patient == null)
        {
            throw new ServiceException("patient not found");
        }
        List<TcmConsultation> consultations = consultationService.selectTcmConsultationList(new TcmConsultation());
        List<TcmAppointment> appointments = appointmentService.selectTcmAppointmentList(new TcmAppointment());
        if (PrivacyUtils.canAccessPatient(patient, consultations, appointments))
        {
            return;
        }
        if (canCreateFirstConsultationForUnassignedPatient(patient, consultations, consultation))
        {
            return;
        }
        throw new ServiceException("access denied");
    }

    private boolean canCreateFirstConsultationForUnassignedPatient(
            TcmPatient patient,
            List<TcmConsultation> consultations,
            TcmConsultation consultation)
    {
        if (PrivacyUtils.isAdmin() || !PrivacyUtils.hasRole("practitioner") || patient == null || consultation == null)
        {
            return false;
        }
        if (patient.getPractitionerId() != null && !patient.getPractitionerId().trim().isEmpty())
        {
            return false;
        }
        String currentUserId = String.valueOf(SecurityUtils.getUserId());
        if (!currentUserId.equals(consultation.getPractitionerId()))
        {
            return false;
        }
        for (TcmConsultation existing : consultations)
        {
            if (existing != null
                    && patient.getId().equals(existing.getPatientId())
                    && (existing.getDeletedAt() == null || existing.getDeletedAt().trim().isEmpty()))
            {
                return false;
            }
        }
        return true;
    }

    private void ensureConsultationAccessible(TcmConsultation consultation)
    {
        TcmPatient patient = patientService.selectTcmPatientById(consultation.getPatientId());
        List<TcmAppointment> appointments = appointmentService.selectTcmAppointmentList(new TcmAppointment());
        if (!PrivacyUtils.canAccessConsultation(consultation, patient, appointments))
        {
            throw new ServiceException("access denied");
        }
    }
}
