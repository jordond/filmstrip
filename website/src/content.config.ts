import { defineCollection } from 'astro:content';
import { docsLoader } from '@astrojs/starlight/loaders';
import { docsSchema } from '@astrojs/starlight/schema';
import { z } from 'astro/zod';

export const collections = {
	docs: defineCollection({
		loader: docsLoader(),
		schema: docsSchema({
			extend: z.object({
				// A playground sample the page shows in its right column on wide screens and inline on narrow ones.
				playground: z.object({ sample: z.string(), label: z.string() }).optional(),
			}),
		}),
	}),
};
