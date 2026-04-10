package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"

	pb "factry-historian-proxy/proto/historianpb"
)

type Config struct {
	Host            string `json:"host"`
	Port            int    `json:"port"`
	CollectorUUID   string `json:"collectorUUID"`
	Token           string `json:"token"`
	MeasurementUUID string `json:"measurementUUID"`
}

func main() {
	data, _ := os.ReadFile("config.json")
	var cfg Config
	json.Unmarshal(data, &cfg)

	target := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	conn, err := grpc.NewClient(target,
		grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{InsecureSkipVerify: true})))
	if err != nil {
		log.Fatal(err)
	}
	defer conn.Close()

	client := pb.NewHistorianClient(conn)
	ctx := metadata.AppendToOutgoingContext(context.Background(),
		"collectoruuid", cfg.CollectorUUID,
		"authorization", "Bearer "+cfg.Token,
	)

	now := time.Now()
	start := now.Add(-1 * time.Hour)
	limit := int64(100)
	offset := int64(0)
	desc := false
	join := false
	onlyChanges := false

	req := &pb.QueryTimeseriesRequest{
		MeasurementUUIDs:  []string{cfg.MeasurementUUID},
		AssetPropertyUUIDs: []string{},
		Measurements:      []*pb.MeasurementByName{},
		Start:             timestamppb.New(start),
		End:               timestamppb.New(now),
		Tags:              &structpb.Struct{Fields: map[string]*structpb.Value{}},
		GroupBy:           []string{},
		Limit:             &limit,
		Offset:            &offset,
		Desc:              &desc,
		Join:              &join,
		ValueFilters:      []*pb.ValueFilter{},
		OnlyChanges:       &onlyChanges,
	}

	log.Printf("Sending QueryTimeseries with all fields populated")
	log.Printf("  measurementUUID: %s", cfg.MeasurementUUID)
	log.Printf("  start: %s", start.UTC().Format(time.RFC3339))
	log.Printf("  end: %s", now.UTC().Format(time.RFC3339))

	resp, err := client.QueryTimeseries(ctx, req)
	if err != nil {
		log.Fatalf("QueryTimeseries failed: %v", err)
	}

	log.Printf("SUCCESS: %d series returned", len(resp.GetSeries()))
	for _, series := range resp.GetSeries() {
		fmt.Printf("\nMeasurement: %s (uuid=%s, datatype=%s, %d points)\n",
			series.GetMeasurement(), series.GetMeasurementUUID(), series.GetDatatype(), len(series.GetDataPoints()))
		fmt.Printf("%-30s  %s\n", "TIMESTAMP", "VALUE")
		fmt.Println("------------------------------  --------------------")
		for _, pt := range series.GetDataPoints() {
			ts := time.UnixMilli(pt.GetTimestamp()).UTC().Format("2006-01-02 15:04:05.000")
			val := "<null>"
			if pt.GetValue() != nil {
				switch pt.GetValue().GetKind().(type) {
				case *structpb.Value_NumberValue:
					val = fmt.Sprintf("%.4f", pt.GetValue().GetNumberValue())
				case *structpb.Value_BoolValue:
					val = fmt.Sprintf("%t", pt.GetValue().GetBoolValue())
				case *structpb.Value_StringValue:
					val = pt.GetValue().GetStringValue()
				}
			}
			fmt.Printf("%-30s  %s\n", ts, val)
		}
	}
}
