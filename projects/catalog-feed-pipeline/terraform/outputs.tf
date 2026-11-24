output "cluster_name" {
  value = google_container_cluster.this.name
}

output "registry" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "workload_service_account" {
  value = google_service_account.workload.email
}

output "get_credentials" {
  value = "gcloud container clusters get-credentials ${google_container_cluster.this.name} --region ${var.region} --project ${var.project_id}"
}
