# Operator references, not secrets. Record the values in the operator's evidence, not in Git.

output "load_balancer_dns_name" {
  description = "Point the three names here (alias records) once DNS is approved."
  value       = aws_lb.main.dns_name
}

output "host_instance_id" {
  description = "The Session Manager and Run Command target."
  value       = aws_instance.host.id
}

output "backup_bucket" {
  value = aws_s3_bucket.backups.bucket
}

output "evidence_bucket" {
  value = aws_s3_bucket.evidence.bucket
}

output "ecr_repositories" {
  value = { for name, repository in aws_ecr_repository.service : name => repository.repository_url }
}

output "operator_policy_arn" {
  description = "Attach to a named operator the founder approves."
  value       = aws_iam_policy.operator.arn
}

output "launch_lock_parameter" {
  value = aws_ssm_parameter.launch_lock.name
}
