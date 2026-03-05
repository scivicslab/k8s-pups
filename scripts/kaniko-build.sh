#!/bin/bash
# Build a Docker image using Kaniko as a K8s Job.
# Usage: kaniko-build.sh <image-name> <version> [--follow] [--no-cache]
#
# Examples:
#   ./scripts/kaniko-build.sh guacamole-desktop 0.1.0
#   ./scripts/kaniko-build.sh jupyter-lab 4.5.5 --follow
#   ./scripts/kaniko-build.sh guacamole-desktop 0.1.0 --no-cache --follow
#
# The script:
#   1. Ensures a PV/PVC for the build context exists in the k8s-pups namespace
#   2. Creates a K8s Job running gcr.io/kaniko-project/executor
#   3. Mounts the build context from devteam-works NFS via PVC
#   4. Pushes the built image to the local registry (192.168.5.23:32000)
#   5. Optionally follows logs until completion (--follow)

set -euo pipefail

REGISTRY="192.168.5.23:32000"
NAMESPACE="k8s-pups"
# NFS details for the build context (devteam's works directory)
NFS_SERVER="192.168.5.20"
NFS_PATH="/Public/Users/devteam/works"
PV_NAME="kaniko-build-context-pv"
PVC_NAME="kaniko-build-context-pvc"
# Path within the NFS share to the docker build contexts
BUILD_CONTEXT_BASE="k8s-pups/docker"

# --- Parse arguments ---
IMAGE_NAME="${1:-}"
VERSION="${2:-}"
FOLLOW=false
NO_CACHE=false

if [ -z "$IMAGE_NAME" ] || [ -z "$VERSION" ]; then
    echo "Usage: $0 <image-name> <version> [--follow] [--no-cache]"
    echo ""
    echo "Available images:"
    ls -1 "$(dirname "$0")/../docker/" 2>/dev/null || echo "  (none found)"
    exit 1
fi

shift 2
for arg in "$@"; do
    case "$arg" in
        --follow) FOLLOW=true ;;
        --no-cache) NO_CACHE=true ;;
        *) echo "Unknown option: $arg"; exit 1 ;;
    esac
done

TIMESTAMP=$(date +%y%m%d%H%M)
TAG="${VERSION}-${TIMESTAMP}"
DESTINATION="${REGISTRY}/${IMAGE_NAME}:${TAG}"
JOB_NAME="kaniko-${IMAGE_NAME}"
CONTEXT_PATH="${BUILD_CONTEXT_BASE}/${IMAGE_NAME}"

# --- Validate build context exists locally ---
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_CONTEXT="${SCRIPT_DIR}/../docker/${IMAGE_NAME}"
if [ ! -d "$LOCAL_CONTEXT" ]; then
    echo "ERROR: Build context not found: ${LOCAL_CONTEXT}"
    exit 1
fi
if [ ! -f "${LOCAL_CONTEXT}/Dockerfile" ]; then
    echo "ERROR: Dockerfile not found in ${LOCAL_CONTEXT}"
    exit 1
fi

# --- Ensure PV/PVC for build context ---
if ! kubectl get pv "${PV_NAME}" >/dev/null 2>&1; then
    echo "Creating PV: ${PV_NAME}"
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: PersistentVolume
metadata:
  name: ${PV_NAME}
spec:
  capacity:
    storage: 500Gi
  accessModes:
    - ReadOnlyMany
  persistentVolumeReclaimPolicy: Retain
  csi:
    driver: nfs.csi.k8s.io
    readOnly: true
    volumeHandle: "${NFS_SERVER}#${NFS_PATH}#kaniko"
    volumeAttributes:
      server: "${NFS_SERVER}"
      share: "${NFS_PATH}"
EOF
fi

if ! kubectl get pvc "${PVC_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1; then
    echo "Creating PVC: ${PVC_NAME} in ${NAMESPACE}"
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ${PVC_NAME}
  namespace: ${NAMESPACE}
spec:
  accessModes:
    - ReadOnlyMany
  resources:
    requests:
      storage: 500Gi
  volumeName: ${PV_NAME}
  storageClassName: ""
EOF
fi

# --- Check for existing job and delete if present ---
if kubectl get job "${JOB_NAME}" -n "${NAMESPACE}" >/dev/null 2>&1; then
    echo "Deleting existing job: ${JOB_NAME}"
    kubectl delete job "${JOB_NAME}" -n "${NAMESPACE}" --wait=true
fi

echo ""
echo "Building image: ${DESTINATION}"
echo "Build context:  ${CONTEXT_PATH} (from NFS ${NFS_SERVER}:${NFS_PATH})"
echo "Job name:       ${JOB_NAME}"
echo ""

# --- Build Kaniko args ---
KANIKO_ARGS='["--dockerfile=Dockerfile", "--context=/workspace", "--destination='"${DESTINATION}"'", "--insecure"'
if [ "$NO_CACHE" = true ]; then
    KANIKO_ARGS="${KANIKO_ARGS}"', "--cache=false"'
fi
KANIKO_ARGS="${KANIKO_ARGS}]"

# --- Create the Job ---
cat <<EOF | kubectl apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: ${JOB_NAME}
  namespace: ${NAMESPACE}
  labels:
    app: kaniko-build
    image: ${IMAGE_NAME}
    version: "${VERSION}"
    tag: "${TAG}"
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 86400
  template:
    metadata:
      labels:
        app: kaniko-build
        image: ${IMAGE_NAME}
    spec:
      containers:
      - name: kaniko
        image: gcr.io/kaniko-project/executor:latest
        args: ${KANIKO_ARGS}
        resources:
          requests:
            cpu: "2"
            memory: "4Gi"
          limits:
            cpu: "4"
            memory: "8Gi"
        volumeMounts:
        - name: build-context
          mountPath: /workspace
          subPath: ${CONTEXT_PATH}
          readOnly: true
      volumes:
      - name: build-context
        persistentVolumeClaim:
          claimName: ${PVC_NAME}
          readOnly: true
      restartPolicy: Never
EOF

echo ""
echo "Job created: ${JOB_NAME}"
echo ""
echo "Commands:"
echo "  kubectl logs -f job/${JOB_NAME} -n ${NAMESPACE}     # Follow build logs"
echo "  kubectl get job ${JOB_NAME} -n ${NAMESPACE}         # Check job status"
echo "  kubectl delete job ${JOB_NAME} -n ${NAMESPACE}      # Delete job"
echo ""
echo "Image will be pushed to: ${DESTINATION}"

if [ "$FOLLOW" = true ]; then
    echo ""
    echo "--- Following logs ---"
    # Wait for pod to be created
    POD=""
    for i in $(seq 1 30); do
        POD=$(kubectl get pods -n "${NAMESPACE}" -l "job-name=${JOB_NAME}" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
        if [ -n "$POD" ]; then
            break
        fi
        sleep 2
    done

    if [ -z "$POD" ]; then
        echo "ERROR: Pod not created after 60s"
        exit 1
    fi

    # Wait for container to start, then follow logs
    kubectl wait --for=condition=Ready "pod/${POD}" -n "${NAMESPACE}" --timeout=120s 2>/dev/null || true
    kubectl logs -f "job/${JOB_NAME}" -n "${NAMESPACE}"

    # Check final status
    SUCCEEDED=$(kubectl get job "${JOB_NAME}" -n "${NAMESPACE}" -o jsonpath='{.status.succeeded}' 2>/dev/null || echo "0")
    if [ "$SUCCEEDED" = "1" ]; then
        echo ""
        echo "BUILD SUCCEEDED: ${DESTINATION}"
    else
        echo ""
        echo "BUILD FAILED. Check logs above."
        exit 1
    fi
fi
