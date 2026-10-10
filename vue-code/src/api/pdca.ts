import { request } from '@/utils/request'

export type PdcaRole = 'P' | 'D2' | 'C' | 'A' | 'UNKNOWN'
export type PdcaType = 'requirement' | 'bug'
export interface PdcaEvidence {
  evidenceId: string
  safeTitle: string
  kind: 'HISTORICAL' | 'D_DEV' | 'C_ACCEPTANCE' | 'A_RELEASE'
  candidateVersion: string | null
  candidateSha: string | null
  safeSummary: string
  sourceRef: string
  observedAt: string
}
export interface PdcaRecord {
  key: string
  originalId: string
  requirementId?: string
  bugId?: string
  sourceRef: string
  recordKind: 'REQUIREMENT' | 'ACCEPTANCE_CHECK' | 'GATE' | 'DEFECT'
  title: string
  batch: string
  ownerRole: PdcaRole
  implementationState?: string
  status?: string
  devState: string
  checkState: string
  releaseState?: string
  severity: string
  blockerType?: string
  blockerSummary: string
  nextStep: string
  updatedAt: string
  evidenceIds: string[]
  evidence: PdcaEvidence[]
}
export interface PdcaMetadata {
  snapshotAvailable: boolean
  reason?: string
  revision: string | null
  sourceRevision?: string
  sourceLedgerSha256?: string
  generatedAt?: string
  updatedAt?: string
  sourceObservedAt?: string
  lastSuccessfulPublishAt?: string
  coverageDefinition?: string
  coverageComplete: boolean
  sourceHealth: string
  stale: boolean
  staleReason?: string | null
  revisionChanged?: boolean
  publishedByRole?: PdcaRole | 'COORD'
  sourceTitles?: Array<{ sourceId: string; safeTitle: string }>
  inventory?: {
    ledgerSourceRecordCount: number
    additionalRequirementOrGateCount: number
    defectRecordCount: number
    independentFeatureDenominator: number | null
    acceptedCurrentCandidateCount: number | null
  }
}
export interface PdcaOverview extends PdcaMetadata {
  roles: Array<{ role: PdcaRole; responsibility: string; currentBatch: string; state: string;
    updatedAt: string; sourceRef: string; nextStep: string; blockers: string[] }>
  counts?: { registeredItems: number; registeredBugs: number; recordKinds: Record<string, number>;
    implementation: Record<string, number>; check: Record<string, number>;
    bugStatus: Record<string, number>; bugSeverity: Record<string, number> }
  filterOptions?: { batches: string[] }
}
export interface PdcaItems extends PdcaMetadata {
  records: PdcaRecord[]
  total: number
  page: number
  pageSize: number
}
export interface PdcaQuery {
  type: PdcaType
  ownerRole?: PdcaRole
  batch?: string
  state?: string
  severity?: string
  keyword?: string
  page: number
  pageSize: number
  knownRevision?: string
}
export function getPdcaOverview(knownRevision?: string, signal?: AbortSignal) {
  return request<PdcaOverview>({ url: '/pdca/overview', method: 'get', params: { knownRevision }, signal, silent: true })
}
export function getPdcaItems(params: PdcaQuery, signal?: AbortSignal) {
  return request<PdcaItems>({ url: '/pdca/items', method: 'get', params, signal, silent: true })
}
