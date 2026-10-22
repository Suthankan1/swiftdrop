#!/usr/bin/env python3
# ==============================================================================
# SwiftDrop Platform — Private Object Storage Bucket Provisioner
# Ensures the private S3 bucket (swiftdrop-pod) exists and is private.
# Safe & Idempotent: Never deletes existing buckets or overwrites objects.
# ==============================================================================
import os
import sys

try:
    import botocore.session
    from botocore.exceptions import ClientError
except ImportError:
    print("[ERROR] botocore is required. Install via pip.")
    sys.exit(1)

def main():
    endpoint = os.environ.get("S3_ENDPOINT", "http://127.0.0.1:9000")
    bucket_name = os.environ.get("SWIFTDROP_STORAGE_BUCKET", "swiftdrop-pod")
    region = os.environ.get("SWIFTDROP_STORAGE_REGION", "us-east-1")
    root_user = os.environ.get("MINIO_ROOT_USER", "swiftdrop_storage_admin")
    root_password = os.environ.get("MINIO_ROOT_PASSWORD", "minio_dev_password")

    session = botocore.session.get_session()
    client = session.create_client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=root_user,
        aws_secret_access_key=root_password,
        region_name=region,
    )

    try:
        client.head_bucket(Bucket=bucket_name)
        print(f"Bucket '{bucket_name}' already exists.")
    except ClientError:
        print(f"Creating private bucket '{bucket_name}'...")
        client.create_bucket(Bucket=bucket_name)
        print(f"Bucket '{bucket_name}' created successfully.")

if __name__ == "__main__":
    main()
