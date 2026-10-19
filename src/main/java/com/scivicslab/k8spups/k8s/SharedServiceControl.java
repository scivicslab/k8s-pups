package com.scivicslab.k8spups.k8s;

/**
 * Starts and stops shared services (cluster-wide Deployments such as the GPU broker). A plain POJO; the controller
 * runs one instance as the actor {@code shared-service-control}, so two administrators pressing Launch and Stop at
 * the same time are served one after the other.
 */
public class SharedServiceControl {

    /** Name the controller registers this service under in the actor system. */
    public static final String ACTOR_NAME = "shared-service-control";

    private final K8sApiClient k8sClient;

    public SharedServiceControl(K8sApiClient k8sClient) {
        this.k8sClient = k8sClient;
    }

    /** Scales the Deployment to {@code replicas} (0 = stop). */
    public void scale(String namespace, String deployment, int replicas) {
        k8sClient.scaleSharedService(namespace, deployment, replicas);
    }
}
