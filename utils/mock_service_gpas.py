from flask import Flask, request, Response
import random
import string

app = Flask(__name__)

def random_string(length=20):
    return ''.join(random.choices(string.ascii_uppercase + string.digits, k=length))

@app.route("/gpas/gpasService", methods=["POST"])
def gpas_service():
    xml = request.data.decode("utf-8")
    print(xml)

    if "getOrCreatePseudonymFor" in xml:
        value = random_string()
        response_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:psn="http://psn.ttp.ganimed.icmvc.emau.org/">
   <soapenv:Header/>
   <soapenv:Body>
      <psn>{value}</psn>
   </soapenv:Body>
</soapenv:Envelope>"""
        print(f"value: {value}")
        return Response(response_xml, mimetype='text/xml')

    elif "createPseudonymFor" in xml:
        value = random_string()
        response_xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:psn="http://psn.ttp.ganimed.icmvc.emau.org/">
   <soapenv:Header/>
   <soapenv:Body>
      <psn>{value}</psn>
   </soapenv:Body>
</soapenv:Envelope>"""
        print(f"value: {value}")
        return Response(response_xml, mimetype='text/xml')

    else:
        return Response("Invalid request", status=400)

if __name__ == "__main__":
    app.run(port=8082)
