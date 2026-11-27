# L2-12: the operational launch lock. It is created CLOSED and Terraform never changes it again
# (ignore_changes), so an apply can neither lift nor re-close it behind the founder's back. Lifting
# needs a recorded founder authorization and the separate launch-lock-lift policy (iam.tf), which is
# attached to nobody by this code. Lifting is not part of LAUNCH-002.

resource "aws_ssm_parameter" "launch_lock" {
  name        = "/divalhr/production/launch-lock"
  description = "CLOSED until LAUNCH-003 and an explicit founder authorization"
  type        = "String"
  value       = "CLOSED"

  lifecycle {
    ignore_changes = [value]
  }
}
