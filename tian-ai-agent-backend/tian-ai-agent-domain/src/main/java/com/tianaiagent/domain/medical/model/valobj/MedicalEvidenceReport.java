package com.tianaiagent.domain.medical.model.valobj;

import java.util.List;

public record MedicalEvidenceReport(String title, String riskLevel, String summary,
                                    List<String> redFlags, List<String> evidence,
                                    List<String> uncertainties, List<String> nextSteps,
                                    boolean requiresProfessionalReview) {}
