FROM frolvlad/alpine-oraclejre8:full

LABEL maintainer="H.Hegardt <henrikhegardtgithub@gmail.com>"

ENV JITSTATIC_VERSION v0.7.3
ENV WORK_DIR /home/jitstatic
ENV VOL ${WORK_DIR}/db
ENV JITSTATIC_FILE jitstatic-${JITSTATIC_VERSION}.jar
ENV JITSTATIC_CONF config.yml

WORKDIR ${WORK_DIR}

RUN wget -O ${JITSTATIC_FILE} https://github.com/hheg/jitstatic/releases/download/${JITSTATIC_VERSION}/${JITSTATIC_FILE}

ADD docker_setup.yml ${JITSTATIC_CONF}

VOLUME ${VOL} 

EXPOSE 8085

ENTRYPOINT exec java \
	-Ddw.hosted.basePath=${VOL} \
	-Ddw.hosted.userName=${USER} \
	-Ddw.hosted.secret=${PASS} \
	-jar ${JITSTATIC_FILE} \
	server \
	${JITSTATIC_CONF}