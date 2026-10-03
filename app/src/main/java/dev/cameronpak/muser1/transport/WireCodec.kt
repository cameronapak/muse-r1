/* Copyright (c) Meta Platforms, Inc. and affiliates. Licensed under Apache-2.0. */
package dev.cameronpak.muser1.transport

import java.io.ByteArrayOutputStream

internal class ProtocolException(message: String) : Exception(message)
internal data class Header(val key: String, val value: String)
internal sealed interface ServiceValue
internal data class ApplicationRequest(val verb: String, val path: String, val headers: List<Header>, val body: ByteArray, val end: Boolean) : ServiceValue
internal data class ApplicationResponse(val status: Int, val headers: List<Header>, val body: ByteArray, val end: Boolean) : ServiceValue
internal data class BodyChunk(val data: ByteArray, val end: Boolean) : ServiceValue
internal data class Reset(val code: Int, val reason: String) : ServiceValue
internal data class ServiceFrame(val streamId: Long, val value: ServiceValue)
internal data class NoiseFrame(val chunkId: Long, val index: Int, val total: Int, val payload: ByteArray)

internal object WireCodec {
    const val MAX_CHUNK = 65_489
    const val MAX_CHUNKS = 256
    const val MAX_ASSEMBLY = 16 * 1024 * 1024

    private fun varint(value: Long): ByteArray { var v=value; val o=ByteArrayOutputStream(); do { var b=(v and 127).toInt(); v=v ushr 7; if(v!=0L)b=b or 128; o.write(b) } while(v!=0L); return o.toByteArray() }
    private fun field(n:Int,v:Long)=varint((n*8).toLong())+varint(v)
    private fun bytes(n:Int,v:ByteArray)=varint((n*8+2).toLong())+varint(v.size.toLong())+v
    private fun string(n:Int,v:String)=bytes(n,v.toByteArray(Charsets.UTF_8))
    private fun parts(vararg p:ByteArray):ByteArray { val o=ByteArrayOutputStream(); p.forEach{o.write(it)}; return o.toByteArray() }
    private fun header(h:Header)=parts(if(h.key.isEmpty()) byteArrayOf() else string(1,h.key),if(h.value.isEmpty()) byteArrayOf() else string(2,h.value))

    fun encodeService(frame:ServiceFrame):ByteArray {
        val value=when(val v=frame.value){
            is ApplicationRequest -> parts(if(v.verb.isEmpty()) byteArrayOf() else string(1,v.verb),if(v.path.isEmpty()) byteArrayOf() else string(2,v.path),*v.headers.map{bytes(3,header(it))}.toTypedArray(),if(v.body.isEmpty()) byteArrayOf() else bytes(4,v.body),if(v.end)field(5,1)else byteArrayOf())
            is ApplicationResponse -> parts(if(v.status==0)byteArrayOf()else field(1,v.status.toLong()),*v.headers.map{bytes(2,header(it))}.toTypedArray(),if(v.body.isEmpty())byteArrayOf()else bytes(3,v.body),if(v.end)field(4,1)else byteArrayOf())
            is BodyChunk -> parts(if(v.data.isEmpty())byteArrayOf()else bytes(1,v.data),if(v.end)field(2,1)else byteArrayOf())
            is Reset -> parts(if(v.code==0)byteArrayOf()else field(1,v.code.toLong()),if(v.reason.isEmpty())byteArrayOf()else string(2,v.reason))
        }
        val kind=when(frame.value){is ApplicationRequest->2;is ApplicationResponse->3;is BodyChunk->4;is Reset->5}
        return parts(if(frame.streamId==0L)byteArrayOf()else field(1,frame.streamId),bytes(kind,value))
    }
    fun requestEnvelope(frame:ServiceFrame):ByteArray = bytes(2,encodeService(frame)) // daemon=0 omitted
    fun responseEnvelope(frame:ServiceFrame):ByteArray = bytes(1,encodeService(frame))

    fun encodeNoise(frame:NoiseFrame)=parts(if(frame.chunkId==0L)byteArrayOf()else field(1,frame.chunkId),if(frame.index==0)byteArrayOf()else field(2,frame.index.toLong()),field(3,frame.total.toLong()),if(frame.payload.isEmpty())byteArrayOf()else bytes(4,frame.payload))
    fun noiseFrames(data:ByteArray,id:Long=java.security.SecureRandom().nextLong()):List<ByteArray>{ val total=maxOf(1,(data.size+MAX_CHUNK-1)/MAX_CHUNK); require(total<=MAX_CHUNKS); return (0 until total).map{ i->encodeNoise(NoiseFrame(id,i,total,data.copyOfRange(i*MAX_CHUNK,minOf(data.size,(i+1)*MAX_CHUNK)))) } }

    private class Reader(val b:ByteArray){var p=0; fun vi():Long {var r=0L;var s=0;repeat(10){if(p>=b.size)throw ProtocolException("truncated varint");val x=b[p++].toInt()and 255;if(it==9&&x>1)throw ProtocolException("malformed varint");r=r or ((x and 127).toLong() shl s);if(x and 128==0)return r;s+=7};throw ProtocolException("malformed varint")};fun del():ByteArray{val n=vi();if(n>Int.MAX_VALUE||p+n>b.size)throw ProtocolException("truncated field");return b.copyOfRange(p,(p+n).toInt().also{p=it})};fun skip(w:Int){when(w){0->vi();1->{p+=8};2->del();5->{p+=4};else->throw ProtocolException("invalid wire type")};if(p>b.size)throw ProtocolException("truncated field")}}
    private fun fields(data:ByteArray, fn:(Int,Int,Reader)->Unit){val r=Reader(data);while(r.p<data.size){val k=r.vi();val n=(k ushr 3).toInt();val w=(k and 7).toInt();if(n==0)throw ProtocolException("invalid field");fn(n,w,r)}}
    fun decodeNoise(data:ByteArray):NoiseFrame{var id=0L;var i=0;var total=1;var payload=byteArrayOf();fields(data){n,w,r->when(n){1->id=r.vi();2->i=r.vi().toInt();3->total=r.vi().toInt();4->payload=r.del();else->r.skip(w)}};return NoiseFrame(id,i,total,payload)}
    fun decodeResponseEnvelope(data:ByteArray):ServiceFrame {var payload:ByteArray?=null;fields(data){n,w,r->if(n==1)payload=r.del()else r.skip(w)};return decodeService(payload?:throw ProtocolException("empty response envelope"))}
    private fun decodeHeader(data:ByteArray):Header{var k="";var v="";fields(data){n,w,r->when(n){1->k=r.del().toString(Charsets.UTF_8);2->v=r.del().toString(Charsets.UTF_8);else->r.skip(w)}};return Header(k,v)}
    fun decodeService(data:ByteArray):ServiceFrame {var id=0L;var kind=0;var raw=byteArrayOf();fields(data){n,w,r->if(n==1)id=r.vi()else if(n in 2..5){kind=n;raw=r.del()}else r.skip(w)};var status=0;val hs=mutableListOf<Header>();var body=byteArrayOf();var end=false;var code=0;var reason="";fields(raw){n,w,r->when(kind){3->when(n){1->status=r.vi().toInt();2->hs+=decodeHeader(r.del());3->body=r.del();4->end=r.vi()!=0L;else->r.skip(w)};4->when(n){1->body=r.del();2->end=r.vi()!=0L;else->r.skip(w)};5->when(n){1->code=r.vi().toInt();2->reason=r.del().toString(Charsets.UTF_8);else->r.skip(w)};else->r.skip(w)}};val v=when(kind){3->ApplicationResponse(status,hs,body,end);4->BodyChunk(body,end);5->Reset(code,reason);else->throw ProtocolException("unexpected service frame")};return ServiceFrame(id,v)}
}

internal class NoiseReassembler(private val now:()->Long={System.currentTimeMillis()}) {
    private data class A(val total:Int,val at:Long,val chunks:MutableMap<Int,ByteArray>,var bytes:Int=0)
    private val pending=mutableMapOf<Long,A>()
    fun add(encoded:ByteArray):ByteArray? { pending.entries.removeIf{now()-it.value.at>60_000};val f=WireCodec.decodeNoise(encoded);if(f.total !in 1..WireCodec.MAX_CHUNKS||f.index !in 0 until f.total||f.payload.size>WireCodec.MAX_CHUNK)throw ProtocolException("invalid noise frame");val a=pending[f.chunkId]?:run{if(pending.size>=16)throw ProtocolException("too many assemblies");A(f.total,now(),mutableMapOf()).also{pending[f.chunkId]=it}};if(a.total!=f.total||a.chunks.put(f.index,f.payload)!=null){pending.remove(f.chunkId);throw ProtocolException("inconsistent or duplicate chunk")};a.bytes+=f.payload.size;if(a.bytes>WireCodec.MAX_ASSEMBLY){pending.remove(f.chunkId);throw ProtocolException("assembly too large")};if(a.chunks.size<a.total)return null;pending.remove(f.chunkId);return (0 until a.total).fold(ByteArray(0)){x,i->x+a.chunks.getValue(i)}}
}
