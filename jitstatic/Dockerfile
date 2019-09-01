FROM ubuntu:18.04 as build

ENV TINI_VERSION v0.18.0
RUN apt update && apt install wget -y
RUN wget -o /dmp https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini \
    && chmod +x /tini

FROM adoptopenjdk:11-jre-hotspot

LABEL maintainer="H.Hegardt <henrikhegardtgithub@gmail.com>"

ARG JAR_FILE
ENV WORK_DIR /home/jitstatic
ENV VOL ${WORK_DIR}/db
ENV JITSTATIC_FILE $JAR_FILE
ENV JITSTATIC_CONF config/config.yml
ENV JAVA_OPTS=

RUN useradd -ms /bin/bash jitstatic

RUN apt update && apt-mark hold dash && apt install less -y \
    && apt-get -y clean \
    && rm -rf /var/lib/apt/lists/*

ENV TINI_VERSION v0.18.0
COPY --from=build /tini /tini

WORKDIR ${WORK_DIR}

ADD $JITSTATIC_FILE $JITSTATIC_FILE

ADD docker_dw_setup.yml ${JITSTATIC_CONF}

RUN mkdir -p /var/log/jitstatic \
    && mkdir -p /home/jitstatic/db \
    && chown jitstatic:jitstatic /var/log/jitstatic /home/jitstatic/db

USER jitstatic

VOLUME ${VOL} 

EXPOSE 8085

ENTRYPOINT ["/tini","--"]

CMD java ${JAVA_OPTS} -Ddw.hosted.basePath=${VOL} -Ddw.hosted.userName=${USER} -Ddw.hosted.secret=${PASS} -jar ${WORK_DIR}/${JITSTATIC_FILE} server ${WORK_DIR}/${JITSTATIC_CONF}
 