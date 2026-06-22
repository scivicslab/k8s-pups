FROM yomitoku-ocr:latest
RUN pip install requests
COPY convert.py /app/convert.py
WORKDIR /data
ENTRYPOINT ["python3", "/app/convert.py"]
