package play.modules.riak;

import static play.modules.riak.RiakPlugin.riak;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;

import com.basho.riak.pbc.KeySource;
import com.basho.riak.pbc.MapReduceResponseSource;
import com.basho.riak.pbc.RequestMeta;
import com.basho.riak.pbc.RiakLink;
import com.basho.riak.pbc.RiakObject;
import com.basho.riak.pbc.mapreduce.JavascriptFunction;
import com.basho.riak.pbc.mapreduce.MapReduceBuilder;
import com.basho.riak.pbc.mapreduce.MapReduceResponse;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;

public class RiakModel {
	
	// Raw object
	private RiakObject obj;
	
	public RiakObject getObj(){
		return obj;
	}
	public void setObj(RiakObject obj){
		this.obj = obj;
	}
	
	public Map<String,String> getUserMeta(){
		return obj.getUsermeta();
	}
	
	public void setUserMeta(Map<String,String> usermeta){
		for ( String key : usermeta.keySet() ) {
			obj.addUsermetaItem(key, usermeta.get(key));
		}
		//obj.setUserMeta(usermeta);
	}	
	
	public static String generateUID(){
		return  String.valueOf(UUID.randomUUID());
	}

	public String toJSON(){
		RiakObject o = null;
		// Hack to prevent serialisation of obj
		if(this.obj != null){
			o = this.obj;
		}
		
		String jsonValue = new Gson().toJson(this);
		
		this.obj = o;
		return jsonValue;
	}
	
	public boolean save() {
		Logger.debug("RiakModel save %s", this.toString());

		RiakObject o = null;
		// Hack to prevent serialisation of obj
		if(this.obj != null){
			o = this.obj;
			
		}
		this.obj = null;

		String jsonValue = new Gson().toJson(this);
		RiakPath path = this.getPath();

		if(path != null){
			Logger.debug("Create new object %s, %s", path.getBucket(), path.getValue());
			
			this.obj = new RiakObject(path.getBucket(), path.getValue(), jsonValue);
			if(o != null && o.getLinks() != null) {
				for ( RiakLink link : o.getLinks() ) {
					obj.addLink(link.getTag(), link.getBucket(), link.getKey());
				}
				//this.obj.setLinks(o.getLinks());
			}
			try {
				riak.store(this.obj);
				this.setObj(this.obj);
				return true;
			} catch (IOException e) {
				Logger.error("Error during save of %s", jsonValue);
				e.printStackTrace();
				return false;
			}
		}else{
			return false;
		}
	}

	public RiakPath getPath(){
		Class clazz = this.getClass();
		
		RiakKey key = RiakPlugin.bucketMap.get(clazz.getName());
		try {
			Field f = clazz.getField(key.getKey());
			String value = f.get(this).toString();
			RiakPath path = new RiakPath(value, key);
			return path;
		} catch (Exception e) {
			Logger.error("Error %s during reflextion of class: %s with path: %s", e.getMessage(), clazz.getName(), key.toString());
			e.printStackTrace();
			return null;
		}
	}
	
	public static <T extends RiakModel> List<T> findAll(Class clazz){
		throw new UnsupportedOperationException("Please annotate your model with @RiakEntity annotation.");
	}
	public static <T extends RiakModel> List<T> findAll(String bucket){
		throw new UnsupportedOperationException("Please annotate your model with @RiakEntity annotation.");
	}
	
	public static List findOrderByDate(Class clazz, Type returnType, boolean reverse){

		MapReduceBuilder builder = new MapReduceBuilder(RiakPlugin.riak);
		builder.setBucket(RiakPlugin.getBucketName(clazz));
		builder.map(JavascriptFunction.anon(RiakMapReduce.function.get("orderByCreationDateMap")), false);
		if(reverse)
			builder.reduce(JavascriptFunction.anon(RiakMapReduce.function.get("orderByCreateDateDescReduce")), false);
		else
			builder.reduce(JavascriptFunction.anon(RiakMapReduce.function.get("orderByCreateDateAscReduce")), false);
		
		builder.reduce(JavascriptFunction.anon(RiakMapReduce.function.get("cleanReduce")), true);
		try {
			MapReduceResponseSource mrs = builder.submit(new RequestMeta().contentType("application/json"));
			String res = "";
			while(mrs.hasNext()){
				MapReduceResponse mr = mrs.next();
				ByteString bs = mr.getContent();
				if(bs != null && !bs.isEmpty())
					res += bs.toStringUtf8();
			}
			
			if(!res.isEmpty()){
				List jsonResult = new Gson().fromJson(res, returnType);
				return jsonResult;
			}
		}catch (IOException e) {
			Logger.error("Error during findOrderByDate");
			e.printStackTrace();
		}	
	
		return null;
	}
	public static List findOrderByDate(Class clazz, Type returnType){
		return findOrderByDate(clazz, returnType, false);
	}
	
	//TODO: fix in enhancer
	public static <T extends RiakModel> List<T> fetch(Class clazz, Type returnType, int start, int end){
		throw new UnsupportedOperationException("Please annotate your model with @RiakEntity annotation.");
	}
	
	public static List<String> findKeys(Class clazz){
		return findKeys(RiakPlugin.getBucketName(clazz)); 
	}
	public static List<String> findKeys(String bucket){
		try {
			KeySource keySource = riak.listKeys(ByteString.copyFromUtf8(bucket));
			List<String> list = new ArrayList<String>();
			for (ByteString bs : keySource) {
				list.add(bs.toStringUtf8());
			}
			return list;
		} catch (IOException e) {
			Logger.error("Error during listKeys for bucket: %s", bucket);
			e.printStackTrace();
			return null;
		}		
	}
	
	public static <T extends RiakModel> T find(Class clazz, String key){
		throw new UnsupportedOperationException("Please annotate your model with @RiakEntity annotation.");
	}
	public static <T extends RiakModel> T find(String bucket, String key){
		throw new UnsupportedOperationException("Please annotate your model with @RiakEntity annotation.");
	}	
	
	public static void delete(Class clazz, String key){
		delete(RiakPlugin.getBucketName(clazz), key);
	}
	public static void delete(String bucket, String key){
		try {
			riak.delete(bucket, key);
		} catch (IOException e) {
			Logger.error("Error during deletion of bucket: %s, key: %s", bucket, key);
			e.printStackTrace();
		}	
	}
	
	public boolean delete(){
		RiakPath path = this.getPath();
		if(path != null){
			Logger.debug("Delete bucket: %s , keyValue %s", path.getBucket(), path.getValue());
			delete(path.getBucket(), path.getValue());
		}		
		return false;
	}
	public static void deleteAll(Class clazz){
		deleteAll(RiakPlugin.getBucketName(clazz));
	}
	public static void deleteAll(String bucket){
		List<String> keys = RiakModel.findKeys(bucket);
		
		for(String key: keys){
			RiakModel.delete(bucket, key);
		}
	}
	public List<RiakModel> getLinksByTag(String tag){

		List<RiakModel> result = new ArrayList<RiakModel>();
		
		List<RiakLink> links = obj.getLinks();
		Set<String> keys = RiakPlugin.bucketMap.keySet();
		for (RiakLink link : links) {
			
			if(link.getTag().toStringUtf8().equals(tag)){
				String bucket  = link.getBucket().toStringUtf8();	
				
				for(String key: keys){
					RiakKey riakKey = RiakPlugin.bucketMap.get(key);
					if(riakKey.getBucket().equals(bucket)){
						try{
							ApplicationClass clazz = Play.classes.getApplicationClass(key);
							Method m = clazz.javaClass.getMethod("find", new Class[]{String.class, String.class});
							RiakModel r = (RiakModel)m.invoke(new Object(), bucket, link.getKey().toStringUtf8());						
							result.add(r);
						} catch (Exception e) {
							Logger.error("Errror during reflection");
							e.printStackTrace();
						}							
						break;					
					}
				}
			}
		}		
		
		return result;		
	}
	
	public List<RiakModel> getLink(){
		List<RiakModel> result = new ArrayList<RiakModel>();
		
		List<RiakLink> links = obj.getLinks();
		if(links ==null)
			return result;
		
		Set<String> keys = RiakPlugin.bucketMap.keySet();
		for (RiakLink link : links) {
			String bucket  = link.getBucket().toStringUtf8();	
			
			for(String key: keys){
				RiakKey riakKey = RiakPlugin.bucketMap.get(key);
				if(riakKey.getBucket().equals(bucket)){
					
					try{
						ApplicationClass clazz = Play.classes.getApplicationClass(key);
						Method m = clazz.javaClass.getMethod("find", new Class[]{String.class, String.class});
						RiakModel r = (RiakModel)m.invoke(new Object(), bucket, link.getKey().toStringUtf8());						
						result.add(r);
					} catch (Exception e) {
						Logger.error("Errror during reflection");
						e.printStackTrace();
					}		
					
					break;					
				}
			}
		}		
		
		return result;
	}
	
	public List<RiakLink> getRawLink(){
		if(obj != null)
			return obj.getLinks();
		else
			return new ArrayList<RiakLink>();
	}
	
	public List<RiakLink> getRawLinksByTag(String tag){
		List<RiakLink> res = new ArrayList<RiakLink>();
		
		if(this.obj != null){
			List<RiakLink> links = obj.getLinks();
			for (RiakLink link : links) {
				if(link.getTag().toStringUtf8().equals(tag)){
					res.add(link);
				}
			}
		}
		
		return res;		
	}

	public void addLink(Class clazz, String key, String tag){
		this.addLink(RiakPlugin.getBucketName(clazz), key, tag);
	}	
	public void addLink(String bucket, String key, String tag){
		obj.addLink(tag, bucket, key);
	}
	
	public static <T extends RiakModel> List<T> jsonToList(String json, Type listType){
		throw new UnsupportedOperationException("Please annotate your model with @RiakEntity annotation.");
	}
	
	public static List orderBy(Class clazz, String field, Boolean reverse , Type listType){
		Object[] args = {field, reverse};
		MapReduceBuilder builder = new MapReduceBuilder();
		builder.setBucket(RiakPlugin.getBucketName(clazz));
		builder.setRiakClient(play.modules.riak.RiakPlugin.riak);
		builder.map(JavascriptFunction.named("Riak.mapValuesJson"), false);
		builder.reduce(JavascriptFunction.anon(RiakMapReduce.function.get("orderByReduce")), args, true);
		try {
			MapReduceResponseSource mrs = builder.submit(new RequestMeta().contentType("application/json"));
			while(mrs.hasNext()){
				MapReduceResponse mr = mrs.next();
				ByteString bs = mr.getContent();
				if(bs != null && !bs.isEmpty()){
					return new com.google.gson.Gson().fromJson(bs.toStringUtf8(), listType);
				}
			}			
		}catch (IOException e) {
			e.printStackTrace();
		}				
		
		return null;
	}
	
//	public static List orderBy(Class clazz, String field, Boolean reverse , Type listType){
//		Object[] args = {field, reverse};
//		try {
//			MapReduceResponse r = play.modules.riak.RiakPlugin.riak.mapReduceOverBucket(play.modules.riak.RiakPlugin.getBucketName(clazz))
//				.map(com.basho.riak.client.mapreduce.JavascriptFunction.named("Riak.mapValuesJson"), false)
//				.reduce(com.basho.riak.client.mapreduce.JavascriptFunction.anon(play.modules.riak.RiakMapReduce.orderByReduceString), args, true).submit();
//			if (r.isSuccess()) {
//				return new com.google.gson.Gson().fromJson(r.getBodyAsString(), listType);
//			}
//			else{
//				return null;
//			}
//		} catch (org.json.JSONException e) {
//			e.printStackTrace();
//			return null;
//		}
//	}
}
