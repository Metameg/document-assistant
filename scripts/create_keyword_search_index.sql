-- Index the weighted text expression used by KeywordChunkSearchService.
-- Run with psql autocommit, outside a transaction block.

CREATE INDEX CONCURRENTLY IF NOT EXISTS
    document_chunk_vectors_keyword_fts_idx
ON public.document_chunk_vectors
USING GIN ((
    setweight(
        to_tsvector(
            'english',
            coalesce(metadata->>'section', '')
        ),
        'A'
    )
    ||
    setweight(
        to_tsvector(
            'english',
            coalesce(content, '')
        ),
        'D'
    )
));
