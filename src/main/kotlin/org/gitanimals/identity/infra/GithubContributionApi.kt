package org.gitanimals.identity.infra

import org.gitanimals.identity.app.ContributionApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@Component
class GithubContributionApi(
    @Value("\${github.token}") private val token: String,
) : ContributionApi {

    private val restClient = RestClient.create("https://api.github.com/graphql")
    private val executors = Executors.newFixedThreadPool(32)

    override fun getContributionCountWithToken(
        username: String,
        years: List<Int>
    ): Map<Int, Int> {
        val contributionCountResponses = callGetContributionCountApis(token, years, username)

        val ans = mutableMapOf<Int, Int>()
        years.withIndex().forEach {
            val index = it.index
            val year = it.value
            val completableFuture = contributionCountResponses[index]
            ans[year] = completableFuture.get()
        }

        return ans
    }


    private fun callGetContributionCountApis(
        token: String,
        years: List<Int>,
        username: String
    ): MutableList<CompletableFuture<Int>> {
        val completableFutures = mutableListOf<CompletableFuture<Int>>()
        years.forEach { year ->
            val completableFuture = CompletableFuture.supplyAsync({
                restClient.post()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .body(
                        mapOf(
                            "query" to contributionCountByYearQuery
                                .replaceFirst(NAME_FIX, username)
                                .replace(YEAR_FIX, year.toString())
                        )
                    )
                    .exchange { _, response ->
                        assertIsSuccess(response)

                        response.bodyTo(ContributionCountByYearQueryResponse::class.java)!!
                            .data
                            .user
                            .contributionsCollection
                            .contributionCalendar
                            .totalContributions
                    }
            }, executors)

            completableFutures.add(completableFuture)
        }
        return completableFutures
    }

    override fun getAllContributionYearsWithToken(username: String): List<Int> {
        return restClient.post()
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body(mapOf("query" to contributionYearQuery.replace(NAME_FIX, username)))
            .exchange { _, response ->
                assertIsSuccess(response)

                response.bodyTo(ContributionYearQueryResponse::class.java)!!
                    .data
                    .user
                    .contributionsCollection
                    .contributionYears
            }
    }

    private fun assertIsSuccess(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse) {
        require(response.statusCode.is2xxSuccessful) {
            "Bad request cause status : \"${response.statusText}\" message : \"${
                response.bodyTo(
                    String::class.java
                )
            }\""
        }
    }

    private class ContributionYearQueryResponse(val data: Data) {
        class Data(val user: User) {
            class User(val contributionsCollection: ContributionsCollection) {
                class ContributionsCollection(
                    val contributionYears: List<Int>,
                )
            }
        }
    }

    private class ContributionCountByYearQueryResponse(val data: Data) {
        class Data(val user: User) {
            class User(val contributionsCollection: ContributionsCollection) {
                class ContributionsCollection(
                    val contributionCalendar: ContributionCalendar,
                ) {
                    class ContributionCalendar(
                        val totalContributions: Int,
                    )
                }
            }

        }
    }

    companion object {
        private const val NAME_FIX = "*{name}"
        private const val YEAR_FIX = "*{year}"

        private val contributionYearQuery: String =
            ClassPathResource("github-graphql/contribution-year.graphql")
                .getContentAsString(Charset.defaultCharset())

        private val contributionCountByYearQuery =
            ClassPathResource("github-graphql/contribution-count-by-year.graphql")
                .getContentAsString(Charset.defaultCharset())
    }
}
