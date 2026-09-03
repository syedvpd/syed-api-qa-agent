import { redirect } from "next/navigation";

export default function ResultsPage({ params }: { params?: { id?: string } }) {
  if (params?.id) {
    redirect(`/runs/${params.id}/results`);
  }
  redirect("/dashboard");
}
