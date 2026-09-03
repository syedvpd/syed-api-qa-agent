import { redirect } from "next/navigation";

export default function ReportsRedirectPage({ params }: { params?: { id?: string } }) {
  if (params?.id) {
    redirect(`/runs/${params.id}/report`);
  }
  redirect("/dashboard");
}
