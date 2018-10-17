FROM openjdk:10.0.2-13-jre-slim-sid

LABEL maintainer="H.Hegardt <henrikhegardtgithub@gmail.com>"

ARG JAR_FILE
ENV WORK_DIR /home/jitstatic
ENV VOL ${WORK_DIR}/db
ENV JITSTATIC_FILE $JAR_FILE
ENV JITSTATIC_CONF config.yml
ENV JAVA_OPTS=

RUN apt update && apt-mark hold dash && apt upgrade -y && apt install less

WORKDIR ${WORK_DIR}

ADD $JITSTATIC_FILE $JITSTATIC_FILE

ADD docker_dw_setup.yml ${JITSTATIC_CONF}

VOLUME ${VOL} 

EXPOSE 8085

ENTRYPOINT exec java \
	${JAVA_OPTS} \
	-Ddw.hosted.basePath=${VOL} \
	-Ddw.hosted.userName=${USER} \
	-Ddw.hosted.secret=${PASS} \
	-jar ${JITSTATIC_FILE} \
	server \
	${JITSTATIC_CONF}