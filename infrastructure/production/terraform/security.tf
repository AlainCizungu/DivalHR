# L2-3: only the load balancer is public. The host accepts one application listener, only from the
# load balancer's security group. There is no inbound SSH rule anywhere.

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Public load balancer: 443 and the 80 redirect"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http_redirect" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP, answered only with a redirect to 443"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_host" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Application listener of the host only"
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  referenced_security_group_id = aws_security_group.host.id
}

resource "aws_security_group" "host" {
  name        = "${var.name_prefix}-host"
  description = "Application host: the load balancer in, endpoints out"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-host" }
}

resource "aws_vpc_security_group_ingress_rule" "host_from_alb" {
  security_group_id            = aws_security_group.host.id
  description                  = "Caddy listener, only from the load balancer"
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "host_to_endpoints" {
  security_group_id            = aws_security_group.host.id
  description                  = "AWS service endpoints"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = aws_security_group.endpoints.id
}

data "aws_prefix_list" "s3" {
  name = "com.amazonaws.${var.region}.s3"
}

resource "aws_vpc_security_group_egress_rule" "host_to_s3" {
  security_group_id = aws_security_group.host.id
  description       = "S3 gateway endpoint (backups, releases, image layers)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = data.aws_prefix_list.s3.id
}

# Transactional e-mail: one rule per approved provider range, none by default (fail closed).
resource "aws_vpc_security_group_egress_rule" "host_to_email_provider" {
  for_each          = toset(var.email_provider_cidrs)
  security_group_id = aws_security_group.host.id
  description       = "Approved transactional e-mail provider, submission port only"
  ip_protocol       = "tcp"
  from_port         = var.email_submission_port
  to_port           = var.email_submission_port
  cidr_ipv4         = each.value
}

resource "aws_security_group" "endpoints" {
  name        = "${var.name_prefix}-endpoints"
  description = "VPC interface endpoints: HTTPS from the host only"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.name_prefix}-endpoints" }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_host" {
  security_group_id            = aws_security_group.endpoints.id
  description                  = "HTTPS from the application host"
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  referenced_security_group_id = aws_security_group.host.id
}
