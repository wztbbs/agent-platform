package com.cexpilot.platform.policy.approval;

/** 方案是否获准执行；与执行进度分开保存。 */
public enum PlanApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, SUPERSEDED }
