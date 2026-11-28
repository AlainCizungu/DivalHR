# L2-5, L2-10: least privilege. The host role can read only this environment's secrets, write its
# own backup and evidence prefixes, pull its own images and publish its own metrics. It cannot
# delete a backup, change the launch lock or read another environment.

data "aws_iam_policy_document" "host_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "host" {
  name               = "${var.name_prefix}-host"
  assume_role_policy = data.aws_iam_policy_document.host_assume.json
}

locals {
  secret_prefix_arn = "arn:${data.aws_partition.current.partition}:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:divalhr/production/*"
  lock_param_arn    = "arn:${data.aws_partition.current.partition}:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/divalhr/production/launch-lock"
}

data "aws_iam_policy_document" "host" {
  # Session Manager and Run Command agent calls (these actions take no resource constraint).
  statement {
    sid = "SessionManagerAgent"
    actions = [
      "ssm:UpdateInstanceInformation",
      "ssm:ListInstanceAssociations",
      "ssm:ListAssociations",
      "ssm:GetDocument",
      "ssm:DescribeDocument",
      "ssm:GetManifest",
      "ssm:UpdateInstanceAssociationStatus",
      "ssm:PutComplianceItems",
      "ssm:PutInventory",
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
      "ec2messages:AcknowledgeMessage",
      "ec2messages:DeleteMessage",
      "ec2messages:FailMessage",
      "ec2messages:GetEndpoint",
      "ec2messages:GetMessages",
      "ec2messages:SendReply",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "ReadOwnSecrets"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [local.secret_prefix_arn]
  }

  statement {
    sid       = "ReadLaunchLock"
    actions   = ["ssm:GetParameter"]
    resources = [local.lock_param_arn]
  }

  statement {
    sid       = "UseOwnKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [aws_kms_key.main.arn]
  }

  statement {
    sid       = "WriteAndReadBackups"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.backups.arn}/backups/*"]
  }

  statement {
    sid       = "ListBackups"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.backups.arn]
  }

  statement {
    sid       = "ReadReleasesWriteEvidence"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.evidence.arn}/releases/*", "${aws_s3_bucket.evidence.arn}/verification/*", "${aws_s3_bucket.evidence.arn}/evidence/*"]
  }

  statement {
    sid       = "ListReleases"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.evidence.arn]
  }

  statement {
    sid       = "EcrToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "PullOwnImages"
    actions   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
    resources = concat([for repository in aws_ecr_repository.service : repository.arn], [aws_ecr_repository.mirror.arn])
  }

  statement {
    sid       = "PublishOwnMetrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["Divalhr/Production"]
    }
  }

  statement {
    sid       = "WriteOwnHostLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.host.arn}:*"]
  }
}

resource "aws_iam_role_policy" "host" {
  name   = "divalhr-production-host"
  role   = aws_iam_role.host.id
  policy = data.aws_iam_policy_document.host.json
}

resource "aws_iam_instance_profile" "host" {
  name = "${var.name_prefix}-host"
  role = aws_iam_role.host.name
}

# ---- the operator's deployment policy (attached by the founder to a named operator, never here) --
data "aws_iam_policy_document" "operator" {
  statement {
    sid       = "RunCommandOnTheHostOnly"
    actions   = ["ssm:SendCommand", "ssm:StartSession"]
    resources = [aws_instance.host.arn]
  }

  statement {
    sid       = "RunShellDocument"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:${data.aws_partition.current.partition}:ssm:${var.region}::document/AWS-RunShellScript"]
  }

  statement {
    sid       = "ReadCommandResults"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations", "ssm:DescribeSessions", "ssm:TerminateSession"]
    resources = ["*"]
  }

  statement {
    sid       = "UploadReleases"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.evidence.arn}/releases/*", "${aws_s3_bucket.evidence.arn}/verification/*", "${aws_s3_bucket.evidence.arn}/evidence/*"]
  }

  statement {
    sid       = "ReadTheLaunchLockAndListEvidence"
    actions   = ["ssm:GetParameter"]
    resources = [local.lock_param_arn]
  }

  statement {
    sid       = "ListEvidence"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.evidence.arn]
  }

  statement {
    sid       = "EcrToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PushAndCheckOwnImages"
    actions = [
      "ecr:DescribeImages",
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = concat([for repository in aws_ecr_repository.service : repository.arn], [aws_ecr_repository.mirror.arn])
  }

  # Creates the generated secrets once (secrets-bootstrap.sh). The operator can NEVER read a secret value.
  statement {
    sid       = "CreateOwnSecretsOnly"
    actions   = ["secretsmanager:CreateSecret", "secretsmanager:DescribeSecret", "secretsmanager:TagResource"]
    resources = [local.secret_prefix_arn]
  }

  statement {
    sid       = "UseOwnKeyForUploads"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.main.arn]
  }
}

resource "aws_iam_policy" "operator" {
  name   = "${var.name_prefix}-operator-deploy"
  policy = data.aws_iam_policy_document.operator.json
}

# The only identity that may change the launch lock. Created, attached to nobody: lifting the lock
# is a recorded founder authorization (L2-12), not part of LAUNCH-002.
data "aws_iam_policy_document" "launch_lock_lift" {
  statement {
    sid       = "ChangeTheLaunchLock"
    actions   = ["ssm:PutParameter"]
    resources = [local.lock_param_arn]
  }
}

resource "aws_iam_policy" "launch_lock_lift" {
  name   = "${var.name_prefix}-launch-lock-lift"
  policy = data.aws_iam_policy_document.launch_lock_lift.json
}
