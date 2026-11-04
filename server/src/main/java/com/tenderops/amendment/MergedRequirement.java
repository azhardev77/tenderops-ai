package com.tenderops.amendment;

import com.tenderops.compliance.RequirementView;

/** One merged requirement: persisted row id plus engine input. */
public record MergedRequirement(long requirementId, RequirementView view) {
}
