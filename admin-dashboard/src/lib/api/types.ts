/**
 * Kiểu dữ liệu khớp DTO thật của backend (qr-order-backend: AuthDtos, UserDtos, MenuDtos, TableDtos,
 * SettingsDtos). Thời điểm là chuỗi ISO-8601; BigDecimal (vatRate, serviceFeeRate) là number trong JSON.
 */

export type UserRole = "ADMIN" | "MANAGER" | "STAFF" | "KITCHEN";
export type UserStatus = "ACTIVE" | "LOCKED" | "DISABLED";

/** 8 permission nghiệp vụ (enum `Permission` ở backend). */
export const PERMISSIONS = [
  "PAYMENT_PROCESS",
  "BILL_DISCOUNT",
  "BILL_VOID",
  "REFUND_RECORD",
  "ITEM_CANCEL_APPROVE",
  "SESSION_FORCE_CLOSE",
  "SESSION_REOPEN",
  "REPORT_VIEW",
] as const;
export type Permission = (typeof PERMISSIONS)[number];

export interface ErrorDetail {
  field?: string;
  message: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ------------------------------------------------------------------------------------------------ Auth / User

export interface CurrentUser {
  id: number;
  username: string;
  fullName: string;
  role: UserRole;
  status: UserStatus;
  permissions: Permission[];
  lastLoginAt: string | null;
  passwordChangedAt: string | null;
}

export interface TokenResponse {
  tokenType: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: CurrentUser;
}

export interface UserDto {
  id: number;
  username: string;
  fullName: string;
  role: UserRole;
  status: UserStatus;
  permissions: Permission[];
  lastLoginAt: string | null;
  passwordChangedAt: string | null;
  createdBy: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  version: number;
}

export interface PermissionGrant {
  id: number;
  userId: number;
  permission: Permission;
  active: boolean;
  grantedBy: number;
  grantedAt: string | null;
  reason: string;
  reauthenticated: boolean;
  revokedBy: number | null;
  revokedAt: string | null;
  revokeReason: string | null;
}

// ------------------------------------------------------------------------------------------------ Settings

export type OrderConfirmationMode = "FIRST_BATCH_ONLY" | "ALL" | "NONE";

export interface SettingsDto {
  vatRate: number | null;
  vatConfigured: boolean;
  serviceFeeRate: number;
  serviceFeeTaxable: boolean | null;
  sessionIdleTimeoutMinutes: number;
  guestJwtTtlMinutes: number;
  batchClaimTtlSeconds: number;
  maxPendingBatchesPerSession: number;
  joinRateLimitPerMinute: number;
  orderRateLimitPerMinute: number;
  maxItemQuantity: number;
  orderConfirmationMode: OrderConfirmationMode;
  requireTablePin: boolean;
  cleaningAutoRelease: boolean;
  timezone: string;
  moneyRoundingMode: string;
  pricesIncludeVat: boolean;
  updatedBy: number | null;
  updatedAt: string | null;
  version: number;
}

// ------------------------------------------------------------------------------------------------ Menu

export type ProductStatus = "AVAILABLE" | "SOLD_OUT" | "HIDDEN";
export type SelectionType = "SINGLE" | "MULTIPLE";

export interface CategoryDto {
  id: number;
  name: string;
  description: string | null;
  displayOrder: number;
  visible: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  version: number;
}

export interface AssignedOptionGroup {
  optionGroupId: number;
  name: string;
  selectionType: SelectionType;
  required: boolean;
  active: boolean;
  displayOrder: number;
}

export interface ProductDto {
  id: number;
  categoryId: number;
  name: string;
  description: string | null;
  price: number;
  imageUrl: string | null;
  status: ProductStatus;
  displayOrder: number;
  statusChangedAt: string | null;
  statusChangedBy: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  version: number;
  /** Có ở API một món; null ở danh sách phân trang. */
  optionGroups: AssignedOptionGroup[] | null;
}

export interface OptionItemDto {
  id: number;
  optionGroupId: number;
  name: string;
  extraPrice: number;
  active: boolean;
  displayOrder: number;
  createdAt: string | null;
  updatedAt: string | null;
  version: number;
}

export interface OptionGroupDto {
  id: number;
  name: string;
  selectionType: SelectionType;
  required: boolean;
  minSelect: number;
  maxSelect: number | null;
  active: boolean;
  displayOrder: number;
  items: OptionItemDto[];
  createdAt: string | null;
  updatedAt: string | null;
  version: number;
}

// ------------------------------------------------------------------------------------------------ Table / QR

export type TableStatus = "AVAILABLE" | "OCCUPIED" | "PAYMENT_PENDING" | "CLEANING";
export type SessionStatus = "OPEN" | "BILLING" | "CLOSED" | "EXPIRED" | "FORCE_CLOSED";
export type QrCodeStatus = "ACTIVE" | "ROTATED" | "REVOKED";

export interface TableDto {
  id: number;
  code: string;
  displayName: string;
  area: string | null;
  capacity: number;
  status: TableStatus;
  active: boolean;
  displayOrder: number;
  statusChangedAt: string;
  createdAt: string | null;
  updatedAt: string | null;
  version: number;
}

export interface ActiveSessionSummary {
  id: number;
  status: SessionStatus;
  openedAt: string | null;
  lastActivityAt: string;
  needsAttention: boolean;
  deviceCount: number;
  version: number;
}

export interface FloorPlanTable {
  table: TableDto;
  hasActiveQr: boolean;
  activeSession: ActiveSessionSummary | null;
}

export interface QrCodeDto {
  id: number;
  tableId: number;
  status: QrCodeStatus;
  createdAt: string | null;
  createdBy: number | null;
  deactivatedAt: string | null;
  deactivatedBy: number | null;
  deactivationReason: string | null;
  /** Chỉ có với QR ACTIVE dựng lại được token. */
  url: string | null;
  /** false: QR seed dev hoặc đã đổi QR_TOKEN_SECRET; cần xoay mã trước khi in. */
  reproducible: boolean | null;
}

// ------------------------------------------------------------------------------------------------ Phiên, đơn, hóa đơn (Đợt 2)

export type OrderStatus = "OPEN" | "LOCKED" | "PAID" | "VOIDED" | "WRITTEN_OFF";
export type OrderItemStatus = "PENDING" | "CONFIRMED" | "PREPARING" | "READY" | "SERVED" | "CANCELLED" | "REJECTED";
export type BatchStatus = "PENDING" | "ACCEPTED" | "PARTIALLY_ACCEPTED" | "REJECTED" | "CANCELLED" | "COMPLETED";
export type CancelRequestStatus = "PENDING" | "APPROVED" | "DENIED";
export type BillStatus = "ACTIVE" | "VOID_PENDING" | "VOIDED" | "WRITTEN_OFF";
export type PaymentStatus = "UNPAID" | "PARTIALLY_PAID" | "PAID";
export type PaymentMethod = "CASH" | "BANK_TRANSFER" | "CARD_POS" | "OTHER";
export type TransactionStatus = "PENDING" | "SUCCESS" | "FAILED";
export type AttentionType = "BILL_VOID_PENDING" | "AWAITING_REPLACEMENT_BILL";

export interface StaffSessionDto {
  id: number;
  tableId: number;
  tableCode: string | null;
  tableName: string | null;
  area: string | null;
  status: SessionStatus;
  openedByType: "GUEST" | "STAFF";
  openedByUserId: number | null;
  openedAt: string | null;
  lastActivityAt: string;
  verifiedAt: string | null;
  tablePin: string | null;
  needsAttention: boolean;
  attentionReason: "IDLE_WITH_UNPAID_ORDER" | null;
  attentionSetAt: string | null;
  billingStartedAt: string | null;
  closedAt: string | null;
  closedBy: number | null;
  closeReason: string | null;
  deviceCount: number;
  version: number;
}

export interface OrderOptionDto {
  groupName: string;
  optionName: string;
  extraPrice: number;
}

export interface OrderItemDto {
  id: number;
  batchId: number;
  productId: number;
  productName: string;
  unitPrice: number;
  optionExtraPerUnit: number;
  quantity: number;
  lineTotal: number;
  note: string | null;
  status: OrderItemStatus;
  reasonCode: string | null;
  wasted: boolean;
  options: OrderOptionDto[];
  cancelRequest: { id: number; status: CancelRequestStatus; requestedAt: string | null } | null;
  createdAt: string | null;
  confirmedAt: string | null;
  preparingAt: string | null;
  readyAt: string | null;
  servedAt: string | null;
  closedAt: string | null;
  version: number;
}

export interface StaffBatchDto {
  id: number;
  orderId: number;
  sessionId: number;
  tableId: number;
  tableCode: string;
  seq: number;
  status: BatchStatus;
  requiresConfirmation: boolean;
  submittedAt: string | null;
  claimedBy: number | null;
  claimedAt: string | null;
  claimActive: boolean;
  claimExpiresAt: string | null;
  decidedBy: number | null;
  decidedAt: string | null;
  reasonCode: string | null;
  items: OrderItemDto[];
  version: number;
}

export interface StaffOrderDto {
  /** null khi phiên chưa có đơn nào. */
  orderId: number | null;
  sessionId: number;
  tableCode: string;
  status: OrderStatus | null;
  batches: StaffBatchDto[];
  billableTotal: number;
}

export interface CancelRequestDto {
  id: number;
  orderItemId: number;
  orderId: number;
  sessionId: number;
  tableCode: string;
  productName: string;
  quantity: number;
  itemStatusAtRequest: OrderItemStatus;
  currentItemStatus: OrderItemStatus;
  status: CancelRequestStatus;
  requestReason: string | null;
  requestedAt: string | null;
  decidedBy: number | null;
  decidedAt: string | null;
  decisionReason: string | null;
  version: number;
}

export interface BillLineDto {
  itemId: number;
  productName: string;
  options: string[];
  note: string | null;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  status: OrderItemStatus;
}

export interface PaymentDto {
  id: number;
  billId: number;
  receiptNumber: string | null;
  method: PaymentMethod;
  amount: number;
  status: TransactionStatus;
  note: string | null;
  failureReason: string | null;
  createdAt: string | null;
  confirmedAt: string | null;
}

export interface RefundDto {
  id: number;
  billId: number;
  requestId: string;
  receiptNumber: string | null;
  method: PaymentMethod;
  amount: number;
  status: TransactionStatus;
  reason: string;
  failureReason: string | null;
  createdAt: string | null;
  confirmedAt: string | null;
}

export interface BillDto {
  id: number;
  billNumber: string;
  orderId: number;
  orderStatus: OrderStatus;
  sessionId: number;
  sessionStatus: SessionStatus;
  tableId: number;
  tableCode: string;
  status: BillStatus;
  paymentStatus: PaymentStatus;
  subtotalAmount: number;
  discountAmount: number;
  discountReason: string | null;
  netAmount: number;
  serviceFeeAmount: number;
  totalAmount: number;
  vatAmount: number;
  paidAmount: number;
  pendingAmount: number;
  remainingAmount: number;
  replacesBillId: number | null;
  createdAt: string | null;
  paidAt: string | null;
  voidType: "SESSION_REOPEN" | "MANAGER_VOID" | "FORCE_CLOSE_NO_CHARGE" | null;
  voidReason: string | null;
  voidedAt: string | null;
  writtenOffAt: string | null;
  lines: BillLineDto[];
  payments: PaymentDto[];
  version: number;
  refundedAmount: number;
  refundPendingAmount: number;
  remainingRefundable: number;
  voidRequestedAt: string | null;
  orderNeedsAttention: boolean;
  refunds: RefundDto[];
}

export interface BillingAttentionItem {
  type: AttentionType;
  orderId: number;
  sessionId: number;
  sessionStatus: SessionStatus | null;
  tableCode: string | null;
  billId: number | null;
  billNumber: string | null;
  since: string | null;
  remainingRefundable: number | null;
  reason: string | null;
}

// ------------------------------------------------------------------------------------------------ Báo cáo (Đợt 2)

export type ReportGroupBy = "DAY" | "WEEK" | "MONTH" | "SHIFT";

export interface MethodAmount {
  method: PaymentMethod;
  paymentCount: number;
  paymentAmount: number;
  refundCount: number;
  refundAmount: number;
  netAmount: number;
}

export interface RevenuePeriod {
  label: string;
  date: string;
  from: string;
  to: string;
  shiftCode: string | null;
  shiftName: string | null;
  grossRevenue: number;
  refundedAmount: number;
  netRevenue: number;
  paymentCount: number;
  billCount: number;
  refundCount: number;
  writtenOffCount: number;
  writtenOffShortfall: number;
  byMethod: MethodAmount[];
}

export interface RevenueReport {
  from: string;
  to: string;
  fromDate: string;
  toDate: string;
  groupBy: ReportGroupBy;
  shift: string | null;
  totals: {
    grossRevenue: number;
    refundedAmount: number;
    netRevenue: number;
    paymentCount: number;
    billCount: number;
    refundCount: number;
    writtenOffCount: number;
    writtenOffShortfall: number;
    byMethod: MethodAmount[];
  };
  periods: RevenuePeriod[];
}

export interface SummaryReport {
  from: string;
  to: string;
  fromDate: string;
  toDate: string;
  writtenOffCount: number;
  writtenOffShortfall: number;
  refundCount: number;
  refundedAmount: number;
  sessionsOpened: number;
  batchesSubmitted: number;
  batchesByStaff: number;
  items: {
    totalItems: number;
    cancelledItems: number;
    rejectedItems: number;
    /** 0..1 */
    cancelRate: number;
    rejectRate: number;
    wastedItems: number;
    wastedValue: number;
    byReason: Array<{ reasonCode: string; count: number }>;
  };
  ordersNeedingAttention: number;
  billsVoidPending: number;
}

export type TopProductSort = "QUANTITY" | "REVENUE";

export interface TopProduct {
  rank: number;
  productId: number;
  productName: string;
  categoryId: number;
  categoryName: string;
  quantity: number;
  sales: number;
  orderCount: number;
}

export interface TopProductsReport {
  sortBy: TopProductSort;
  limit: number;
  products: TopProduct[];
}

export interface ServiceSegment {
  segment: string;
  sampleCount: number;
  averageSeconds: number | null;
  medianSeconds: number | null;
}

export interface ServiceTimeReport {
  servedItems: number;
  segments: ServiceSegment[];
}
