import { redirect } from "next/navigation";

export default function RunDetailsPage({ params }: { params: { id: string } }) {
  redirect(`/runs/${params.id}/live`);
}

