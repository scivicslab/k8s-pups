# Keycloak of k8s-pups-with-audit (the image of overlays/pups-with-audit/keycloak-image) plus the Protocol Mapper
# keycloak-ga4gh-passport-mapper, which puts the Visas of a Visa issuer in the claim ga4gh_passport_v1 of the UserInfo
# response (PassportKeycloakWithMapper_261026_oo01). The jar is copied into jars/ by hand before the build and is not kept
# in the repository. The build options are those of keycloak-image/Dockerfile; the relative path is fixed at build time.
ARG BASE=192.168.5.13:32000/keycloak-custom:26.8.1-2610190644
FROM ${BASE}

COPY jars/ga4gh-passport-mapper.jar /opt/keycloak/providers/ga4gh-passport-mapper.jar

# The login theme pups-with-audit, copied by hand into theme/ from keycloak-image/templates of the private overlays
# before the build, replaces the copy in the base image, so a theme change does not need the base image rebuilt.
COPY theme/ /opt/keycloak/themes/pups-with-audit/login/

RUN /opt/keycloak/bin/kc.sh build \
    --db=postgres \
    --http-relative-path=/pups-with-audit-auth \
    --http-management-relative-path=/ \
    --health-enabled=true \
    --metrics-enabled=true
