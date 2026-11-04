// TenderOps AI API DTOs. Money is string | null everywhere (CL-06: the client never
// computes with money; it renders the strings unchanged).

export interface TenderListItem {
  id: number;
  title: string;
  reference: string | null;
  latestVersion: number;
  latestDecision: string | null;
  updatedAt: string;
}

export interface TenderListResponse {
  items: TenderListItem[];
  page: number;
  size: number;
  total: number;
}

export interface VersionDto {
  id: number;
  versionNumber: number;
  kind: string;
  reference: string | null;
  estimatedValue: string | null;
  emdAmount: string | null;
  tenderFee: string | null;
  submissionDeadline: string | null;
  pageCount: number;
}

export interface RunSummaryDto {
  runId: number;
  versionId: number;
  decision: string;
  readinessScore: string | null;
  museMode: string;
  samMode: string;
}

export interface TenderDetailResponse {
  id: number;
  title: string;
  reference: string | null;
  createdAt: string;
  updatedAt: string;
  versions: VersionDto[];
  latestRun: RunSummaryDto | null;
}

export interface UploadResponse {
  tenderId: number;
  versionId: number;
  jobId: number | null;
  duplicate: boolean;
}

export interface PageDto {
  pageNo: number;
  classification: string;
  needsHumanReview: boolean;
  reviewReason: string | null;
}

export interface PageTextResponse {
  pageNo: number;
  text: string | null;
}

export interface BboxDto {
  x0: string;
  y0: string;
  x1: string;
  y1: string;
}

export interface RegionDto {
  id: number;
  pageNo: number;
  regionType: string;
  bbox: BboxDto;
  confidence: string;
  source: string;
  contentSource: string | null;
  regionText: string | null;
  structuredTable: unknown;
  cropUrl: string | null;
}

export interface RequirementDto {
  id: number;
  clauseRef: string;
  category: string;
  mandatory: boolean;
  metric: string;
  operator: string;
  amountText: string | null;
  amountValue: string | null;
  countValue: number | null;
  lookbackYears: number | null;
  domainTags: string[];
  certificateName: string | null;
  certificateLevel: number | null;
  conditionTree: string | null;
  sourceDocument: string;
  sourcePage: number;
  sourceRegionId: number | null;
  originalQuote: string;
  quoteVerified: boolean;
  confidence: string | null;
  amendmentAction: string | null;
  targetClauseRef: string | null;
}

export interface RequirementInline {
  id: number;
  clauseRef: string;
  mandatory: boolean;
  metric: string;
  operator: string;
  amountText: string | null;
  sourcePage: number;
  originalQuote: string;
  confidence: string | null;
}

export interface MatrixRow {
  requirement: RequirementInline;
  verdict: string;
  reasonCode: string | null;
  calculationTrace: unknown;
  copied: boolean;
}

export interface TimeSavedDto {
  percent: string;
  manualMinutes: string | null;
  assistedMinutes: string | null;
}

export interface MatrixResponse {
  runId: number;
  museMode: string;
  samMode: string;
  readinessScore: string | null;
  decision: string;
  timeSaved: TimeSavedDto | null;
  rows: MatrixRow[];
}

export interface ReverifyResponse {
  runId: number | null;
  jobId: number;
}

export interface RiskDto {
  severity: string;
  riskCode: string;
  sourceClause: string | null;
  evidenceSummary: string | null;
  detectionReason: string | null;
  businessImpact: string | null;
  correctiveAction: string | null;
}

export interface ChecklistItem {
  clauseRef: string;
  requirement: string;
  missingItem: string;
  correctiveAction: string;
}

export interface ClarificationItem {
  clauseRef: string;
  question: string;
}

export interface SummaryResponse {
  text: string | null;
  groundingWarning: boolean;
  source: string | null;
  museMode: string;
}

export interface DecisionResponse {
  recommendation: string;
  approvalStatus: string;
  readinessScore: string | null;
  warnings: string[];
  decidedAt: string;
}

export interface JobResponse {
  id: number;
  versionId: number;
  status: string;
  currentStage: string | null;
  progress: Record<string, unknown>;
  attempt: number;
  error: string | null;
}

export interface AmendmentUploadResponse {
  versionId: number;
  jobId: number | null;
  amendmentId: number | null;
  duplicate: boolean;
}

export interface AmendmentListItem {
  id: number;
  baseVersionId: number;
  newVersionId: number;
  prevDecision: string | null;
  newDecision: string | null;
  scoreDelta: string | null;
}

export interface ModifiedDto {
  clauseRef: string;
  thresholdChanged: boolean;
  operatorChanged: boolean;
  conditionTreeChanged: boolean;
}

export interface DiffResponse {
  inserted: string[];
  deleted: string[];
  modified: ModifiedDto[];
  deadlineChanged: boolean;
  prevDecision: string | null;
  newDecision: string | null;
  scoreDelta: string | null;
}

export interface ProfileResponse {
  id: number;
  name: string;
  gstNumber: string | null;
  gstRegistered: boolean;
  msmeRegistered: boolean;
  blacklisted: boolean;
  demo: boolean;
  dataVersion: number;
}

export interface FinancialResponse {
  id: number;
  fyLabel: string;
  fyEndDate: string;
  turnover: string | null;
  netWorth: string | null;
  currency: string;
  audited: boolean;
  documentId: number | null;
}

export interface ProjectResponse {
  id: number;
  name: string;
  contractValue: string | null;
  currency: string;
  status: string;
  completionDate: string | null;
  domainCategory: string;
  workOrderDocId: number | null;
  completionCertDocId: number | null;
}

export interface CertificateResponse {
  id: number;
  name: string;
  levelText: string | null;
  levelNumber: number | null;
  issueDate: string | null;
  expiryDate: string | null;
  amount: string | null;
  currency: string;
  documentId: number | null;
}

export interface DocumentResponse {
  id: number;
  fileName: string;
  sha256: string;
  mimeType: string;
  sizeBytes: number;
}

export interface AgentStepDto {
  stepNo: number;
  toolName: string;
  input: unknown;
  output: unknown;
  latencyMs: number | null;
  error: string | null;
}

export interface AgentTraceResponse {
  id: number;
  runId: number;
  requirementId: number | null;
  mode: string;
  museMode: string;
  finalVerdict: string | null;
  steps: AgentStepDto[];
}

export interface DeadlineDto {
  tenderId: number;
  reference: string | null;
  deadline: string;
}

export interface DashboardStats {
  totalTenders: number;
  processingTenders: number;
  completedTenders: number;
  decisionBreakdown: Record<string, number>;
  pendingReviews: number;
  upcomingDeadlines: DeadlineDto[];
  criticalIssues: number;
  performance: { avgMsPerPage: number | null; lastRunMs: number | null };
  timeSaved: {
    percent: string;
    manualMinutes: string | null;
    assistedMinutes: string | null;
    aiMode: string;
    representative: boolean;
  } | null;
}

export interface AuditDto {
  action: string;
  entityType: string;
  entityId: number | null;
  before: string | null;
  after: string | null;
  userId: number | null;
  createdAt: string;
}

export interface AuditListResponse {
  items: AuditDto[];
  page: number;
  size: number;
  total: number;
}

export interface StreamEvent {
  seq: number;
  type: string;
  stage: string | null;
  payload: Record<string, unknown>;
  createdAt: string;
}

