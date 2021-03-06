package hortonworks.hdf.sam.refapp.trucking.simulator.impl.collectors.kinesis;

import hortonworks.hdf.sam.refapp.trucking.simulator.impl.collectors.BaseSerializerTruckEventCollector;
import hortonworks.hdf.sam.refapp.trucking.simulator.impl.domain.transport.EventSourceType;
import hortonworks.hdf.sam.refapp.trucking.simulator.impl.domain.transport.MobileEyeEvent;
import hortonworks.hdf.sam.refapp.trucking.simulator.schemaregistry.TruckSchemaConfig;

import java.nio.ByteBuffer;

import org.apache.avro.generic.GenericRecord;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import com.hortonworks.registries.schemaregistry.SchemaCompatibility;
import com.hortonworks.registries.schemaregistry.SchemaMetadata;
import com.hortonworks.registries.schemaregistry.avro.AvroSchemaProvider;
import com.hortonworks.registries.schemaregistry.serdes.avro.AvroSnapshotSerializer;


public class KinesisEventSerializedInternalWithRegistryEnrichedTopicsCollector extends BaseSerializerTruckEventCollector {


	private AmazonKinesis kinesisClient = null;
	private EventSourceType eventSourceType;

	public KinesisEventSerializedInternalWithRegistryEnrichedTopicsCollector(String regionName, EventSourceType eventSource, String schemaRegistryUrl) {
		super(schemaRegistryUrl);
		this.eventSourceType = eventSource;
 
        try {		
            kinesisClient = createKinesisClient(regionName);    
            validateStream(kinesisClient, TruckSchemaConfig.KAFKA_TRUCK_GEO_EVENT_TOPIC_NAME );
            validateStream(kinesisClient, TruckSchemaConfig.KAFKA_TRUCK_SPEED_EVENT_TOPIC_NAME );

        } catch (Exception e) {
        	logger.error("Error creating Kinesis Client" , e);
        }      
	}
	
	static AmazonKinesis createKinesisClient(String regionName) {
		AmazonKinesisClientBuilder clientBuilder = AmazonKinesisClientBuilder.standard();
        
        clientBuilder.setRegion(regionName);
        clientBuilder.setCredentials(new DefaultAWSCredentialsProviderChain());
        return clientBuilder.build();
        
	}
	
	static void validateStream(AmazonKinesis kClient, String streamName) {
        try {
            DescribeStreamResult result = kClient.describeStream(streamName);
            if(!"ACTIVE".equals(result.getStreamDescription().getStreamStatus())) {
                String errMsg = "Stream " + streamName + " is not active. Please wait a few moments and try again.";
                throw new RuntimeException(errMsg);
            }
        } catch (ResourceNotFoundException e) {
            String errMsg = "Stream " + streamName + " does not exist. Please create it in the console.";
            throw new RuntimeException(errMsg, e);
        } catch (Exception e) {
            String errMsg = "Error found while describing the stream " + streamName;
            throw new RuntimeException(errMsg, e);
        }
    }	

	@Override
	public void onReceive(Object event) throws Exception {

		MobileEyeEvent mee = (MobileEyeEvent) event;
		
		if(eventSourceType == null || EventSourceType.ALL_STREAMS.equals(eventSourceType)) {
			sendTruckEventToKinesis(mee);	
			sendTruckSpeedEventToKinesis(mee);	
		} else if(EventSourceType.GEO_EVENT_STREAM.equals(eventSourceType)) {
			sendTruckEventToKinesis(mee);	
		} else if (EventSourceType.SPEED_STREAM.equals(eventSourceType)) {	
			sendTruckSpeedEventToKinesis(mee);
		}		
	}
	
	
	private void sendTruckEventToKinesis(MobileEyeEvent mee) throws Exception {
		
		
		byte[] serializedPayload = serializeTruckGeoEvent(mee);
    	logger.debug("Creating serialized truck geo event["+serializedPayload+"] for driver["+mee.getTruck().getDriver().getDriverId() + "] in truck [" + mee.getTruck() + "]");

		
		PutRecordRequest putRecord = new PutRecordRequest();
        putRecord.setStreamName(TruckSchemaConfig.KAFKA_TRUCK_GEO_EVENT_TOPIC_NAME);
        
        // We use the driverId as the partition key
        putRecord.setPartitionKey(String.valueOf(mee.getTruck().getDriver().getDriverId()));
        putRecord.setData(ByteBuffer.wrap(serializedPayload));

        try {
            kinesisClient.putRecord(putRecord);
        } catch (AmazonClientException ex) {
			logger.error("Error sending serialized geo event[" + serializedPayload + "] to  Kinesis stream["+TruckSchemaConfig.KAFKA_TRUCK_GEO_EVENT_TOPIC_NAME +"]", ex);
        }		
		
		
	}
		
	
	private void sendTruckSpeedEventToKinesis(MobileEyeEvent mee) throws Exception {

		byte[] serializedPayload = serializeTruckSpeedEvent(mee);
		logger.debug("Creating serialized truck speed event["+serializedPayload+"] for driver["+mee.getTruck().getDriver().getDriverId() + "] in truck [" + mee.getTruck() + "]");			
	
		PutRecordRequest putRecord = new PutRecordRequest();
        putRecord.setStreamName(TruckSchemaConfig.KAFKA_TRUCK_SPEED_EVENT_TOPIC_NAME);
        
        // We use the driverId as the partition key
        putRecord.setPartitionKey(String.valueOf(mee.getTruck().getDriver().getDriverId()));
        putRecord.setData(ByteBuffer.wrap(serializedPayload));
        
        try {
            kinesisClient.putRecord(putRecord);
        } catch (AmazonClientException ex) {
			logger.error("Error sending serialized speed event[" + serializedPayload + "] to  Kafka topic["+TruckSchemaConfig.KAFKA_TRUCK_SPEED_EVENT_TOPIC_NAME +"]", ex);
        }		
		        
	

	}
	
	@Override
	public byte[] serializeTruckGeoEvent(MobileEyeEvent event) throws Exception  {
		
		//get serializer info from registry
		AvroSnapshotSerializer serializer = createSerializer();		
				
		Object truckGeoEvent = createGenericRecordForTruckGeoEvent("/schema/truck-geo-event-kafka.avsc", event);
		((GenericRecord)truckGeoEvent).put("geoAddress", "623 Kerryyton Place Circle"); 
	
       // Now we have the payload in right format (Avro GenericRecord), lets serialize
       SchemaMetadata schemaMetadata = new SchemaMetadata.Builder(TruckSchemaConfig.KAFKA_TRUCK_GEO_EVENT_TOPIC_NAME)
		  .type(AvroSchemaProvider.TYPE)
		  .schemaGroup(TruckSchemaConfig.LOG_SCHEMA_GROUP_NAME)
		  .description("Truck Geo Events from trucks")
		  .compatibility(SchemaCompatibility.BACKWARD)
		  .build();       
		byte[] serializedPaylod = serializer.serialize(truckGeoEvent, schemaMetadata);

		return serializedPaylod;
		
	}	
	
	@Override
	public byte[] serializeTruckSpeedEvent(MobileEyeEvent event) throws Exception  {
		
		//get serializer info from registry
		AvroSnapshotSerializer serializer = createSerializer();		
				
		Object truckGeoEvent = createGenericRecordForTruckSpeedEvent("/schema/truck-speed-event-kafka.avsc", event);

	
       // Now we have the payload in right format (Avro GenericRecord), lets serialize
       SchemaMetadata schemaMetadata = new SchemaMetadata.Builder(TruckSchemaConfig.KAFKA_TRUCK_SPEED_EVENT_TOPIC_NAME)
		  .type(AvroSchemaProvider.TYPE)
		  .schemaGroup(TruckSchemaConfig.LOG_SCHEMA_GROUP_NAME)
		  .description("Truck Speed Events from trucks")
		  .compatibility(SchemaCompatibility.BACKWARD)
		  .build();       
		byte[] serializedPaylod = serializer.serialize(truckGeoEvent, schemaMetadata);

		return serializedPaylod;
		
	}		

}
