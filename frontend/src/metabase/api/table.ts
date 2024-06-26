import type {
  Field,
  GetTableMetadataRequest,
  GetTableRequest,
  Table,
  TableId,
  UpdateTableFieldsOrderRequest,
  UpdateTableListRequest,
  UpdateTableRequest,
} from "metabase-types/api";

import { Api } from "./api";
import { idTag, invalidateTags, listTag, tag } from "./tags";

export const tableApi = Api.injectEndpoints({
  endpoints: builder => ({
    listTables: builder.query<Table[], void>({
      query: () => ({
        method: "GET",
        url: "/api/table",
      }),
      providesTags: (tables = []) => [
        listTag("table"),
        ...(tables.map(({ id }) => idTag("table", id)) ?? []),
      ],
    }),
    getTable: builder.query<Table, GetTableRequest>({
      query: ({ id }) => ({
        method: "GET",
        url: `/api/table/${id}`,
      }),
      providesTags: table => (table ? [idTag("table", table.id)] : []),
    }),
    getTableMetadata: builder.query<Table, GetTableMetadataRequest>({
      query: ({ id, ...body }) => ({
        method: "GET",
        url: `/api/table/${id}/query_metadata`,
        body,
      }),
      providesTags: table => (table ? [idTag("table", table.id)] : []),
    }),
    listTableForeignKeys: builder.query<Field[], TableId>({
      query: id => ({
        method: "GET",
        url: `/api/table/${id}/fks`,
      }),
      providesTags: [listTag("field")],
    }),
    updateTable: builder.mutation<Table, UpdateTableRequest>({
      query: ({ id, ...body }) => ({
        method: "PUT",
        url: `/api/table/${id}`,
        body,
      }),
      invalidatesTags: (_, error, { id }) =>
        invalidateTags(error, [
          idTag("table", id),
          tag("database"),
          tag("card"),
        ]),
    }),
    updateTableList: builder.mutation<Table[], UpdateTableListRequest>({
      query: body => ({
        method: "PUT",
        url: "/api/table",
        body,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("table"), tag("database"), tag("card")]),
    }),
    updateTableFieldsOrder: builder.mutation<
      Table,
      UpdateTableFieldsOrderRequest
    >({
      query: ({ id, ...body }) => ({
        method: "PUT",
        url: `/api/table/${id}/fields/order`,
        body,
        bodyParamName: "field_order",
      }),
      invalidatesTags: (_, error, { id }) =>
        invalidateTags(error, [
          idTag("table", id),
          listTag("field"),
          tag("card"),
        ]),
    }),
    rescanTableFieldValues: builder.mutation<void, TableId>({
      query: id => ({
        method: "POST",
        url: `/api/table/${id}/rescan_values`,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("field-values")]),
    }),
    discardTableFieldValues: builder.mutation<void, TableId>({
      query: id => ({
        method: "POST",
        url: `/api/table/${id}/discard_values`,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("field-values")]),
    }),
  }),
});

export const {
  useListTablesQuery,
  useGetTableQuery,
  useGetTableMetadataQuery,
  useLazyListTableForeignKeysQuery,
  useUpdateTableMutation,
  useUpdateTableListMutation,
  useUpdateTableFieldsOrderMutation,
  useRescanTableFieldValuesMutation,
  useDiscardTableFieldValuesMutation,
} = tableApi;
