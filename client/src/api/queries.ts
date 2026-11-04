import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiMultipart, type AuthResponse, type UserDto } from './client';
import type {
  AgentTraceResponse,
  AmendmentListItem,
  AmendmentUploadResponse,
  AuditListResponse,
  CertificateResponse,
  ChecklistItem,
  ClarificationItem,
  DashboardStats,
  DecisionResponse,
  DiffResponse,
  DocumentResponse,
  FinancialResponse,
  JobResponse,
  MatrixResponse,
  PageDto,
  PageTextResponse,
  ProfileResponse,
  ProjectResponse,
  RegionDto,
  RequirementDto,
  ReverifyResponse,
  RiskDto,
  SummaryResponse,
  TenderDetailResponse,
  TenderListResponse,
  UploadResponse,
  VersionDto,
} from './types';

export function useMe(): UserDto | null {
  const queryClient = useQueryClient();
  return queryClient.getQueryData<UserDto>(['me']) ?? null;
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { email: string; password: string }) =>
      api<AuthResponse>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: (data) => {
      queryClient.setQueryData(['me'], data.user);
    },
  });
}

export function useDashboardStats(enabled = true) {
  return useQuery({
    queryKey: ['dashboard', 'stats'],
    queryFn: () => api<DashboardStats>('/api/v1/dashboard/stats'),
    enabled,
  });
}

export function useTenders(page = 0) {
  return useQuery({
    queryKey: ['tenders', page],
    queryFn: () =>
      api<TenderListResponse>(`/api/v1/tenders?page=${page}&size=20`),
  });
}

export function useTender(id: number | null) {
  return useQuery({
    queryKey: ['tender', id],
    queryFn: () => api<TenderDetailResponse>(`/api/v1/tenders/${id}`),
    enabled: id !== null,
  });
}

export function useVersion(id: number | null) {
  return useQuery({
    queryKey: ['version', id],
    queryFn: () => api<VersionDto>(`/api/v1/tender-versions/${id}`),
    enabled: id !== null,
  });
}

export function usePages(versionId: number | null) {
  return useQuery({
    queryKey: ['pages', versionId],
    queryFn: () => api<PageDto[]>(`/api/v1/tender-versions/${versionId}/pages`),
    enabled: versionId !== null,
  });
}

export function usePageText(versionId: number | null, pageNo: number | null) {
  return useQuery({
    queryKey: ['pageText', versionId, pageNo],
    queryFn: () =>
      api<PageTextResponse>(`/api/v1/tender-versions/${versionId}/pages/${pageNo}/text`),
    enabled: versionId !== null && pageNo !== null,
  });
}

export function useRegions(versionId: number | null, pageNo?: number) {
  const suffix = pageNo === undefined ? '' : `?page=${pageNo}`;
  return useQuery({
    queryKey: ['regions', versionId, pageNo ?? 'all'],
    queryFn: () =>
      api<RegionDto[]>(`/api/v1/tender-versions/${versionId}/regions${suffix}`),
    enabled: versionId !== null,
  });
}

export function useRequirements(versionId: number | null) {
  return useQuery({
    queryKey: ['requirements', versionId],
    queryFn: () => api<RequirementDto[]>(`/api/v1/tender-versions/${versionId}/requirements`),
    enabled: versionId !== null,
  });
}

export function useMatrix(versionId: number | null) {
  return useQuery({
    queryKey: ['matrix', versionId],
    queryFn: () => api<MatrixResponse>(`/api/v1/tender-versions/${versionId}/matrix`),
    enabled: versionId !== null,
    retry: false,
  });
}

export function useRisks(versionId: number | null) {
  return useQuery({
    queryKey: ['risks', versionId],
    queryFn: () => api<RiskDto[]>(`/api/v1/tender-versions/${versionId}/risks`),
    enabled: versionId !== null,
    retry: false,
  });
}

export function useChecklist(versionId: number | null) {
  return useQuery({
    queryKey: ['checklist', versionId],
    queryFn: () =>
      api<ChecklistItem[]>(`/api/v1/tender-versions/${versionId}/checklist`),
    enabled: versionId !== null,
    retry: false,
  });
}

export function useClarifications(versionId: number | null) {
  return useQuery({
    queryKey: ['clarifications', versionId],
    queryFn: () =>
      api<ClarificationItem[]>(`/api/v1/tender-versions/${versionId}/clarifications`),
    enabled: versionId !== null,
    retry: false,
  });
}

export function useSummary(versionId: number | null) {
  return useQuery({
    queryKey: ['summary', versionId],
    queryFn: () => api<SummaryResponse>(`/api/v1/tender-versions/${versionId}/summary`),
    enabled: versionId !== null,
    retry: false,
  });
}

export function useDecision(versionId: number | null) {
  return useQuery({
    queryKey: ['decision', versionId],
    queryFn: () => api<DecisionResponse>(`/api/v1/tender-versions/${versionId}/decision`),
    enabled: versionId !== null,
    retry: false,
  });
}

export function useJob(jobId: number | null) {
  const query = useQuery({
    queryKey: ['job', jobId],
    queryFn: () => api<JobResponse>(`/api/v1/jobs/${jobId}`),
    enabled: jobId !== null,
    refetchInterval: (data) => {
      const status = (data as unknown as JobResponse | undefined)?.status;
      return status === 'QUEUED' || status === 'RUNNING' ? 1000 : false;
    },
  });
  return query;
}

export function useUploadTender() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return apiMultipart<UploadResponse>('/api/v1/tenders', form);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tenders'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });
}

export function useLoadRfp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api<UploadResponse>('/api/v1/demo/load-rfp', { method: 'POST' }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['tenders'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });
}

export function useReverify(versionId: number) {
  return useMutation({
    mutationFn: () =>
      api<ReverifyResponse>(`/api/v1/tender-versions/${versionId}/re-verify`, {
        method: 'POST',
      }),
  });
}

export function useApprove(versionId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (decision: 'APPROVED_FOR_BID' | 'REJECTED_NO_BID') =>
      api<DecisionResponse>(`/api/v1/tender-versions/${versionId}/decision/approve`, {
        method: 'POST',
        body: JSON.stringify({ decision }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['decision', versionId] });
    },
  });
}

export function useAmendments(tenderId: number | null) {
  return useQuery({
    queryKey: ['amendments', tenderId],
    queryFn: () => api<AmendmentListItem[]>(`/api/v1/tenders/${tenderId}/amendments`),
    enabled: tenderId !== null,
  });
}

export function useAmendmentDiff(amendmentId: number | null) {
  return useQuery({
    queryKey: ['amendmentDiff', amendmentId],
    queryFn: () => api<DiffResponse>(`/api/v1/amendments/${amendmentId}/diff`),
    enabled: amendmentId !== null,
    retry: false,
  });
}

export function useUploadAmendment(tenderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, kind }: { file: File; kind: string }) => {
      const form = new FormData();
      form.append('file', file);
      return apiMultipart<AmendmentUploadResponse>(
        `/api/v1/tenders/${tenderId}/amendments?kind=${kind}`,
        form,
      );
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['amendments', tenderId] });
      void queryClient.invalidateQueries({ queryKey: ['tender', tenderId] });
    },
  });
}

export function useLoadCorrigendum(tenderId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      api<AmendmentUploadResponse>(
        `/api/v1/demo/load-corrigendum?tenderId=${tenderId}`,
        { method: 'POST' },
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['amendments', tenderId] });
      void queryClient.invalidateQueries({ queryKey: ['tender', tenderId] });
    },
  });
}

export function useAgentRun(versionId: number) {
  return useMutation({
    mutationFn: (requirementId: number | null) =>
      api<{ agentRunId: number }>(`/api/v1/tender-versions/${versionId}/agent/run`, {
        method: 'POST',
        body: JSON.stringify({ requirementId }),
      }),
  });
}

export function useAgentTrace(agentRunId: number | null) {
  return useQuery({
    queryKey: ['agentTrace', agentRunId],
    queryFn: () => api<AgentTraceResponse>(`/api/v1/agent-runs/${agentRunId}`),
    enabled: agentRunId !== null,
  });
}

export function useProfile() {
  return useQuery({
    queryKey: ['company'],
    queryFn: () => api<ProfileResponse>('/api/v1/company'),
  });
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) =>
      api<ProfileResponse>('/api/v1/company', { method: 'PUT', body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['company'] });
    },
  });
}

export function useFinancials() {
  return useQuery({
    queryKey: ['financials'],
    queryFn: () => api<FinancialResponse[]>('/api/v1/company/financials'),
  });
}

export function useMutateFinancials() {
  const queryClient = useQueryClient();
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['financials'] });
    void queryClient.invalidateQueries({ queryKey: ['company'] });
  };
  return {
    create: useMutation({
      mutationFn: (body: Record<string, unknown>) =>
        api<FinancialResponse>('/api/v1/company/financials', {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      onSuccess: invalidate,
    }),
    update: useMutation({
      mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) =>
        api<FinancialResponse>(`/api/v1/company/financials/${id}`, {
          method: 'PUT',
          body: JSON.stringify(body),
        }),
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: (id: number) =>
        api<void>(`/api/v1/company/financials/${id}`, { method: 'DELETE' }),
      onSuccess: invalidate,
    }),
  };
}

export function useProjects() {
  return useQuery({
    queryKey: ['projects'],
    queryFn: () => api<ProjectResponse[]>('/api/v1/company/projects'),
  });
}

export function useMutateProjects() {
  const queryClient = useQueryClient();
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['projects'] });
    void queryClient.invalidateQueries({ queryKey: ['company'] });
  };
  return {
    create: useMutation({
      mutationFn: (body: Record<string, unknown>) =>
        api<ProjectResponse>('/api/v1/company/projects', {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      onSuccess: invalidate,
    }),
    update: useMutation({
      mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) =>
        api<ProjectResponse>(`/api/v1/company/projects/${id}`, {
          method: 'PUT',
          body: JSON.stringify(body),
        }),
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: (id: number) =>
        api<void>(`/api/v1/company/projects/${id}`, { method: 'DELETE' }),
      onSuccess: invalidate,
    }),
  };
}

export function useCertificates() {
  return useQuery({
    queryKey: ['certificates'],
    queryFn: () => api<CertificateResponse[]>('/api/v1/company/certificates'),
  });
}

export function useMutateCertificates() {
  const queryClient = useQueryClient();
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['certificates'] });
    void queryClient.invalidateQueries({ queryKey: ['company'] });
  };
  return {
    create: useMutation({
      mutationFn: (body: Record<string, unknown>) =>
        api<CertificateResponse>('/api/v1/company/certificates', {
          method: 'POST',
          body: JSON.stringify(body),
        }),
      onSuccess: invalidate,
    }),
    update: useMutation({
      mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) =>
        api<CertificateResponse>(`/api/v1/company/certificates/${id}`, {
          method: 'PUT',
          body: JSON.stringify(body),
        }),
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: (id: number) =>
        api<void>(`/api/v1/company/certificates/${id}`, { method: 'DELETE' }),
      onSuccess: invalidate,
    }),
  };
}

export function useDocuments() {
  return useQuery({
    queryKey: ['documents'],
    queryFn: () => api<DocumentResponse[]>('/api/v1/company/documents'),
  });
}

export function useUploadDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return apiMultipart<DocumentResponse>('/api/v1/company/documents', form);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['documents'] });
    },
  });
}

export function useAuditLogs(entityType: string | null, page: number) {
  const suffix = entityType ? `?entityType=${entityType}&page=${page}&size=20` : `?page=${page}&size=20`;
  return useQuery({
    queryKey: ['audit', entityType ?? 'all', page],
    queryFn: () => api<AuditListResponse>(`/api/v1/audit-logs${suffix}`),
  });
}

export function useUsers() {
  return useQuery({
    queryKey: ['users'],
    queryFn: () => api<UserDto[]>('/api/v1/users'),
    retry: false,
  });
}

export function useSetRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, role }: { id: number; role: string }) =>
      api<UserDto>(`/api/v1/users/${id}/role`, {
        method: 'PATCH',
        body: JSON.stringify({ role }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['users'] });
    },
  });
}
