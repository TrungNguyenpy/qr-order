import { redirect } from "next/navigation";

/** Trang chủ: chuyển tới Tổng quan & Báo cáo (ADMIN thiếu REPORT_VIEW sẽ thấy hướng dẫn cấp quyền). AuthGate xử lý việc chưa đăng nhập. */
export default function Home() {
  redirect("/reports");
}
