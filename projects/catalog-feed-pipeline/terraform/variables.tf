variable "project_id" {
  description = "GCP project id"
  type        = string
}

variable "region" {
  description = "GCP region for the cluster and registry"
  type        = string
  default     = "us-central1"
}

variable "cluster_name" {
  type    = string
  default = "catalog-pipeline"
}

variable "node_machine_type" {
  type    = string
  default = "e2-standard-4"
}

variable "min_nodes" {
  type    = number
  default = 1
}

variable "max_nodes" {
  type    = number
  default = 5
}
